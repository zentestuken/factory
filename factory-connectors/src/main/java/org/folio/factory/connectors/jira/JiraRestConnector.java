package org.folio.factory.connectors.jira;

import org.folio.factory.connectors.ConnectorHealth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.folio.factory.connectors.ConnectorNotConfiguredException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;


import java.net.URI;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Jira REST API v2 client (works with both Data Center and Cloud). Without
 * email/token it reads public issues anonymously and refuses every write.
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

    /** Where Jira Cloud redirects attachment downloads to; it is reached without Jira credentials. */
    private static final String MEDIA_HOST = "api.media.atlassian.com";
    private static final int MAX_REDIRECTS = 3;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    /** HttpRequest.timeout ends when the headers arrive, so a body that stalls is cut off by closing its stream. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "jira-download-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private static final Pattern ATTACHMENT_ID = Pattern.compile("[0-9]{1,18}");

    private final RestClient restClient;
    private final boolean authenticated;
    private final String baseUrl;
    private final String authorization;
    private final Duration downloadTimeout;
    private final HttpClient downloads = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Same transport, deliberately no credentials. Cloned before the Authorization default is
     * added, because the attachment redirect target is a different host that needs no Jira
     * credentials - and must not be sent them.
     */
    private final RestClient mediaClient;

    public JiraRestConnector(JiraProperties properties, RestClient.Builder builder) {
        this(properties, builder, DOWNLOAD_TIMEOUT);
    }

    JiraRestConnector(JiraProperties properties, RestClient.Builder builder, Duration downloadTimeout) {
        this.mediaClient = builder.clone().build();
        this.downloadTimeout = downloadTimeout;
        this.authenticated = properties.isConfigured();
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
        builder.baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (authenticated) {
            this.authorization = "Basic " + HttpHeaders.encodeBasicAuth(
                    properties.email(), properties.apiToken(), StandardCharsets.UTF_8);
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, authorization);
        } else {
            this.authorization = null;
        }
        this.restClient = builder.build();
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
                fields.path("description").isString() ? fields.path("description").asString()
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
        requireWriteAccess();
        restClient.post()
                .uri("/rest/api/2/issue/{key}/comment", issueKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void transitionIssue(String issueKey, String transitionName) {
        requireWriteAccess();
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

    /**
     * Redirects are followed by hand: the Jira credentials go only to the Jira origin, and a redirect
     * may only lead back to it or to Jira Cloud's media host. Errors never carry a URL, because the
     * media redirect has an access token in its query.
     */
    @Override
    public byte[] downloadAttachment(String attachmentId, long maxBytes) {
        if (attachmentId == null || !ATTACHMENT_ID.matcher(attachmentId).matches()) {
            throw new IllegalArgumentException("Invalid Jira attachment id");
        }
        URI base = URI.create(baseUrl);
        URI uri = URI.create(baseUrl + "/rest/api/2/attachment/content/" + attachmentId);
        Instant deadline = Instant.now().plus(downloadTimeout);
        try {
            for (int redirects = 0; ; redirects++) {
                Duration remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative() || remaining.isZero()) {
                    throw new IllegalStateException("Jira attachment download timed out");
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(remaining).GET();
                if (authorization != null && sameOrigin(base, uri)) {
                    request.header(HttpHeaders.AUTHORIZATION, authorization);
                }
                HttpResponse<InputStream> response = downloads.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    int status = response.statusCode();
                    if (status >= 300 && status < 400) {
                        URI next = response.headers().firstValue("Location").map(uri::resolve).orElse(null);
                        if (next == null || redirects >= MAX_REDIRECTS || !redirectAllowed(base, next)) {
                            throw new IllegalStateException("Jira attachment download was redirected somewhere not allowed");
                        }
                        uri = next;
                        continue;
                    }
                    if (status != 200) {
                        throw new IllegalStateException("Jira attachment download failed with HTTP " + status);
                    }
                    if (response.headers().firstValueAsLong("Content-Length").orElse(0) > maxBytes) {
                        throw new IllegalStateException("Jira attachment is larger than " + maxBytes + " bytes");
                    }
                    ScheduledFuture<?> guard = WATCHDOG.schedule(() -> closeQuietly(body),
                            Math.max(1, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS);
                    byte[] bytes;
                    try {
                        bytes = body.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
                    } finally {
                        guard.cancel(false);
                    }
                    if (bytes.length > maxBytes) {
                        throw new IllegalStateException("Jira attachment is larger than " + maxBytes + " bytes");
                    }
                    return bytes;
                }
            }
        } catch (IOException e) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Jira attachment download timed out", e);
            }
            throw new IllegalStateException("Jira attachment download failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Jira attachment download was interrupted", e);
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing only aborts a stalled read.
        }
    }

    static boolean redirectAllowed(URI base, URI target) {
        if (sameOrigin(base, target)) {
            return true;
        }
        return "https".equalsIgnoreCase(target.getScheme()) && MEDIA_HOST.equalsIgnoreCase(target.getHost())
                && port(target) == 443;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void requireWriteAccess() {
        if (!authenticated) {
            throw new ConnectorNotConfiguredException("Jira connector is read-only: set "
                    + "FACTORY_CONNECTORS_JIRA_EMAIL and FACTORY_CONNECTORS_JIRA_API_TOKEN to enable writes");
        }
    }

    @Override
    public String connectorName() {
        return "jira";
    }

    @Override
    public boolean isConfigured() {
        return authenticated;
    }
}
