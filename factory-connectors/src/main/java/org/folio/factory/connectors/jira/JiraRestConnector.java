package org.folio.factory.connectors.jira;

import org.folio.factory.connectors.ConnectorHealth;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jira REST API v2 client (works with both Data Center and Cloud).
 */
public class JiraRestConnector implements JiraConnector, ConnectorHealth {

    // Custom fields whose values are semantically meaningful; all others are stripped.
    // Key = Jira custom field ID, value = name used in the cleaned fields node.
    private static final Map<String, String> KNOWN_CUSTOM_FIELDS = Map.of(
            "customfield_10057", "devTeam"
    );

    private final RestClient restClient;

    public JiraRestConnector(JiraProperties properties, RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + HttpHeaders.encodeBasicAuth(
                        properties.email(), properties.apiToken(), StandardCharsets.UTF_8))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public JiraIssue getIssue(String issueKey) {
        JsonNode body = restClient.get()
                .uri("/rest/api/2/issue/{key}", issueKey)
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
