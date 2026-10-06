package org.folio.factory.connectors.jira;

import org.folio.factory.connectors.ConnectorHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jira REST API v2 client (works with both Data Center and Cloud).
 */
public class JiraRestConnector implements JiraConnector, ConnectorHealth {

    private static final Logger log = LoggerFactory.getLogger(JiraRestConnector.class);

    // Custom fields whose values are semantically meaningful; all others are stripped.
    // Key = Jira custom field ID, value = name used in the cleaned fields node.
    private static final Map<String, String> KNOWN_CUSTOM_FIELDS = Map.of(
            "customfield_10057", "devTeam"
    );

    /** Attachment downloads redirect once to a pre-signed media URL; 3 is slack, not a need. */
    private static final int MAX_ATTACHMENT_REDIRECTS = 3;

    private final RestClient restClient;

    /**
     * Same transport, deliberately no credentials. Cloned before the Authorization default is
     * added, because the attachment redirect target is a different host that needs no Jira
     * credentials - and must not be sent them.
     */
    private final RestClient mediaClient;

    public JiraRestConnector(JiraProperties properties, RestClient.Builder builder) {
        this.mediaClient = builder.clone().build();
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + HttpHeaders.encodeBasicAuth(
                        properties.email(), properties.apiToken(), StandardCharsets.UTF_8))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public JiraIssue getIssue(String issueKey) {
        return getIssue(issueKey, false);
    }

    @Override
    public JiraIssue getIssue(String issueKey, boolean withRenderedBody) {
        JsonNode body = restClient.get()
                .uri(withRenderedBody
                                ? "/rest/api/2/issue/{key}?expand=renderedFields"
                                : "/rest/api/2/issue/{key}",
                        issueKey)
                .retrieve()
                .body(JsonNode.class);
        if (body.path("fields") instanceof ObjectNode fieldsNode) {
            List<String> toRemove = new ArrayList<>();
            Map<String, JsonNode> toAdd = new LinkedHashMap<>();
            for (String name : fieldsNode.propertyNames()) {
                if (name.startsWith("customfield_")) {
                    String semantic = KNOWN_CUSTOM_FIELDS.get(name);
                    JsonNode value = fieldsNode.get(name);
                    if (semantic != null && value != null && !value.isNull()) {
                        toAdd.put(semantic, value);
                    }
                    toRemove.add(name);
                }
            }
            fieldsNode.remove(toRemove);
            toAdd.forEach(fieldsNode::set);
        }
        JsonNode fields = body.path("fields");
        List<String> labels = new ArrayList<>();
        fields.path("labels").forEach(label -> labels.add(label.asString("")));
        return new JiraIssue(
                body.path("key").asString(""),
                fields.path("summary").asString(""),
                fields.path("description").isTextual() ? fields.path("description").asString()
                        : fields.path("description").toString(),
                fields.path("status").path("name").asString(""),
                fields.path("issuetype").path("name").asString(""),
                labels,
                body);
    }

    @Override
    public List<JiraIssue> getLinkedIssues(String issueKey) {
        JsonNode body = restClient.get()
                .uri("/rest/api/2/issue/{key}", issueKey)
                .retrieve()
                .body(JsonNode.class);
        List<String> linkedKeys = new ArrayList<>();
        for (JsonNode link : body.path("fields").path("issuelinks")) {
            JsonNode inward = link.path("inwardIssue");
            if (!inward.isMissingNode()) {
                String k = inward.path("key").asString("");
                if (!k.isBlank()) linkedKeys.add(k);
            }
            JsonNode outward = link.path("outwardIssue");
            if (!outward.isMissingNode()) {
                String k = outward.path("key").asString("");
                if (!k.isBlank()) linkedKeys.add(k);
            }
        }
        return linkedKeys.stream().limit(5).map(this::getIssue).toList();
    }

    /**
     * Attachment bytes. Two hops, which is not optional: Jira answers the attachment content
     * endpoint with <b>303</b> and a {@code Location} pointing at a pre-signed
     * {@code api.media.atlassian.com} URL, and only that second URL carries the image. Spring
     * {@code RestClient} does not follow redirects, so a single-hop implementation silently got an
     * empty body - which is exactly how UIU-3562 reached the gate with both of its mockups marked
     * "download failed".
     *
     * <p>The first hop is authenticated because the Jira endpoint requires it. Every later hop uses
     * {@link #mediaClient}, which carries no credentials: the signed URL authorizes itself, and
     * forwarding Basic auth to another host would leak it for nothing. Enabling redirect-following
     * on the shared client would have done exactly that, and to every other Jira call too.</p>
     *
     * <p>Returns {@code null} rather than throwing on any failure: the caller is enriching a
     * prompt, and a missing image must degrade to "no image" instead of failing a run.</p>
     */
    @Override
    public byte[] downloadAttachment(String contentUrl) {
        try {
            URI uri = URI.create(contentUrl);
            for (int hop = 0; hop <= MAX_ATTACHMENT_REDIRECTS; hop++) {
                ResponseEntity<byte[]> response = (hop == 0 ? restClient : mediaClient).get()
                        .uri(uri)
                        .accept(MediaType.ALL)
                        .retrieve()
                        .toEntity(byte[].class);
                if (!response.getStatusCode().is3xxRedirection()) {
                    return response.getBody();
                }
                URI location = response.getHeaders().getLocation();
                if (location == null) {
                    log.warn("[jira] Attachment {} redirected with no Location header", contentUrl);
                    return null;
                }
                uri = uri.resolve(location);
            }
            log.warn("[jira] Attachment {} exceeded {} redirects", contentUrl, MAX_ATTACHMENT_REDIRECTS);
            return null;
        } catch (Exception e) {
            log.warn("[jira] Could not download attachment {}: {}", contentUrl, e.getMessage());
            return null;
        }
    }

    @Override
    public void addComment(String issueKey, String body) {
        restClient.post()
                .uri("/rest/api/2/issue/{key}/comment", issueKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void transitionIssue(String issueKey, String transitionName) {
        JsonNode transitions = restClient.get()
                .uri("/rest/api/2/issue/{key}/transitions", issueKey)
                .retrieve()
                .body(JsonNode.class);
        String transitionId = null;
        for (JsonNode transition : transitions.path("transitions")) {
            if (transitionName.equalsIgnoreCase(transition.path("name").asString(""))) {
                transitionId = transition.path("id").asString("");
                break;
            }
        }
        if (transitionId == null) {
            throw new IllegalArgumentException(
                    "Issue " + issueKey + " has no transition named '" + transitionName + "'");
        }
        restClient.post()
                .uri("/rest/api/2/issue/{key}/transitions", issueKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("transition", Map.of("id", transitionId)))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public String connectorName() {
        return "jira";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}
