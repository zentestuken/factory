package org.folio.factory.connectors.testrail;

import org.folio.factory.connectors.ConnectorHealth;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TestRail API v2 client.
 */
public class TestRailRestConnector implements TestRailConnector, ConnectorHealth {

    private final RestClient restClient;
    private final TestRailProperties properties;

    public TestRailRestConnector(TestRailProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + HttpHeaders.encodeBasicAuth(
                        properties.username(), properties.apiKey(), StandardCharsets.UTF_8))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public long addCase(long sectionId, String title, String steps, String expected) {
        JsonNode response = restClient.post()
                .uri("/index.php?/api/v2/add_case/{sectionId}", sectionId)
                .body(Map.of(
                        "title", title,
                        "custom_steps", steps == null ? "" : steps,
                        "custom_expected", expected == null ? "" : expected))
                .retrieve()
                .body(JsonNode.class);
        return response.path("id").asLong();
    }

    @Override
    public long addCaseWithMetadata(long sectionId, String title, String steps, String expected,
                                    Map<String, Object> metadata) {
        Map<String, Object> request = new HashMap<>();
        request.put("title", title);
        request.put("custom_steps", steps == null ? "" : steps);
        request.put("custom_expected", expected == null ? "" : expected);
        if (metadata != null) {
            request.putAll(metadata);
        }
        JsonNode response = restClient.post()
                .uri("/index.php?/api/v2/add_case/{sectionId}", sectionId)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        return response.path("id").asLong();
    }

    @Override
    public long addRun(String name, List<Long> caseIds) {
        JsonNode response = restClient.post()
                .uri("/index.php?/api/v2/add_run/{projectId}", properties.projectId())
                .body(Map.of(
                        "name", name,
                        "include_all", false,
                        "case_ids", caseIds))
                .retrieve()
                .body(JsonNode.class);
        return response.path("id").asLong();
    }

    @Override
    public void addResults(long runId, Map<Long, Boolean> results, String comment) {
        List<Map<String, Object>> resultEntries = new ArrayList<>();
        for (Map.Entry<Long, Boolean> result : results.entrySet()) {
            resultEntries.add(Map.of(
                    "case_id", result.getKey(),
                    // TestRail status ids: 1 = passed, 5 = failed
                    "status_id", result.getValue() ? 1 : 5,
                    "comment", comment == null ? "" : comment));
        }
        restClient.post()
                .uri("/index.php?/api/v2/add_results_for_cases/{runId}", runId)
                .body(Map.of("results", resultEntries))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public List<TestRailCase> getCases(long sectionId) {
        List<TestRailCase> all = new ArrayList<>();
        int offset = 0;
        int limit = 250;
        while (true) {
            JsonNode resp = properties.suiteId() != null
                    ? restClient.get()
                            .uri("/index.php?/api/v2/get_cases/{projectId}&suite_id={suiteId}&section_id={sectionId}&limit={limit}&offset={offset}",
                                    properties.projectId(), properties.suiteId(), sectionId, limit, offset)
                            .retrieve().body(JsonNode.class)
                    : restClient.get()
                            .uri("/index.php?/api/v2/get_cases/{projectId}&section_id={sectionId}&limit={limit}&offset={offset}",
                                    properties.projectId(), sectionId, limit, offset)
                            .retrieve().body(JsonNode.class);
            JsonNode cases = resp.path("cases");
            for (JsonNode c : cases) {
                all.add(new TestRailCase(
                        c.path("id").asLong(),
                        c.path("title").asString(""),
                        c.path("custom_preconds").asString("")));
            }
            if (cases.size() < limit) break;
            offset += limit;
        }
        return all;
    }

    @Override
    public long getOrCreateLabel(String title) {
        JsonNode response = restClient.get()
                .uri("/index.php?/api/v2/get_labels/{projectId}", properties.projectId())
                .retrieve()
                .body(JsonNode.class);
        for (JsonNode label : response.path("labels")) {
            if (title.equals(label.path("name").asString(""))) {
                return label.path("id").asLong();
            }
        }
        JsonNode created = restClient.post()
                .uri("/index.php?/api/v2/add_label/{projectId}", properties.projectId())
                .body(Map.of("title", title))
                .retrieve()
                .body(JsonNode.class);
        JsonNode labelNode = created.has("label") ? created.path("label") : created;
        return labelNode.path("id").asLong();
    }

    @Override
    public void deleteCase(long caseId) {
        restClient.post()
                .uri("/index.php?/api/v2/delete_case/{caseId}", caseId)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public long resolveSectionByPath(String path) {
        String[] raw = path.split("\\s*[›|>→]\\s*");
        List<String> parts = new ArrayList<>();
        for (String s : raw) {
            String t = s.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Section path is empty: " + path);
        }

        List<JsonNode> allSections = fetchAllSections();
        Map<Long, List<JsonNode>> byParent = new HashMap<>();
        Map<Long, JsonNode> byId = new HashMap<>();
        for (JsonNode s : allSections) {
            long id = s.path("id").asLong();
            byId.put(id, s);
            JsonNode parentNode = s.path("parent_id");
            long parentKey = (parentNode.isNull() || parentNode.isMissingNode()) ? -1L : parentNode.asLong();
            byParent.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(s);
        }

        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode s : byParent.getOrDefault(-1L, List.of())) {
            if (s.path("name").asString("").equalsIgnoreCase(parts.get(0))) {
                candidates.add(s);
            }
        }
        for (int i = 1; i < parts.size(); i++) {
            String segment = parts.get(i);
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode c : candidates) {
                for (JsonNode child : byParent.getOrDefault(c.path("id").asLong(), List.of())) {
                    if (child.path("name").asString("").equalsIgnoreCase(segment)) {
                        next.add(child);
                    }
                }
            }
            candidates = next;
        }

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No TestRail section found matching path: " + path);
        }
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder("Ambiguous section path '").append(path)
                    .append("' — ").append(candidates.size()).append(" matches: ");
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(buildFullSectionPath(candidates.get(i), byId));
            }
            throw new IllegalArgumentException(sb.toString());
        }
        return candidates.get(0).path("id").asLong();
    }

    @Override
    public List<TestRailSectionRef> getSections() {
        List<JsonNode> all = fetchAllSections();
        Map<Long, JsonNode> byId = new HashMap<>();
        for (JsonNode s : all) byId.put(s.path("id").asLong(), s);
        List<TestRailSectionRef> result = new ArrayList<>();
        for (JsonNode s : all) {
            result.add(new TestRailSectionRef(s.path("id").asLong(), buildFullSectionPath(s, byId)));
        }
        return result;
    }

    @Override
    public List<String> listSectionPaths() {
        List<JsonNode> all = fetchAllSections();
        Map<Long, JsonNode> byId = new HashMap<>();
        for (JsonNode s : all) {
            byId.put(s.path("id").asLong(), s);
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode s : all) {
            if (s.path("depth").asInt(0) <= 2) {
                paths.add(buildFullSectionPath(s, byId));
            }
        }
        paths.sort(null);
        return paths;
    }

    private List<JsonNode> fetchAllSections() {
        List<JsonNode> all = new ArrayList<>();
        int offset = 0;
        int limit = 250;
        String uriTemplate = properties.suiteId() != null
                ? "/index.php?/api/v2/get_sections/{projectId}&suite_id={suiteId}&limit={limit}&offset={offset}"
                : "/index.php?/api/v2/get_sections/{projectId}&limit={limit}&offset={offset}";
        while (true) {
            JsonNode resp = properties.suiteId() != null
                    ? restClient.get()
                            .uri(uriTemplate, properties.projectId(), properties.suiteId(), limit, offset)
                            .retrieve().body(JsonNode.class)
                    : restClient.get()
                            .uri(uriTemplate, properties.projectId(), limit, offset)
                            .retrieve().body(JsonNode.class);
            JsonNode batch = resp.path("sections");
            for (JsonNode s : batch) all.add(s);
            if (batch.size() < limit) break;
            offset += limit;
        }
        return all;
    }

    private String buildFullSectionPath(JsonNode section, Map<Long, JsonNode> byId) {
        List<String> parts = new ArrayList<>();
        JsonNode current = section;
        while (current != null) {
            parts.add(0, current.path("name").asString(""));
            JsonNode parentNode = current.path("parent_id");
            current = (parentNode.isNull() || parentNode.isMissingNode())
                    ? null : byId.get(parentNode.asLong());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" › ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    @Override
    public String connectorName() {
        return "testrail";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}
