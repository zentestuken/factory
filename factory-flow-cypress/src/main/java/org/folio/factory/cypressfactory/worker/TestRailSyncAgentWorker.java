package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestRailSyncAgentWorker implements AgentWorker {

    public static final String ID = "cypress-testrail-sync";

    private static final Logger log = LoggerFactory.getLogger(TestRailSyncAgentWorker.class);
    private static final Map<String, Integer> TIER_TO_TEST_GROUP = Map.of(
            "smoke", 1,
            "criticalpath", 2, "critical", 2,
            "extendedpath", 3, "extended", 3,
            "medium", 2);
    private static final Map<String, Integer> TEAM_TO_DEV_TEAM_ID = Map.of(
            "firebird", 3, "folijet", 4, "spitfire", 6, "thunderjet", 8, "vega", 9,
            "volaris", 13, "citation", 18, "corsair", 19, "eureka", 21, "promin", 27);

    private final TestRailConnector testRail;
    private final FrontmatterCodec frontmatterCodec;
    private final CypressFlowProperties properties;
    private final JsonMapper jsonMapper;

    public TestRailSyncAgentWorker(TestRailConnector testRail, FrontmatterCodec frontmatterCodec,
                                   CypressFlowProperties properties, JsonMapper jsonMapper) {
        this.testRail = testRail;
        this.frontmatterCodec = frontmatterCodec;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String testPlan = context.requireInput("test_plan.md").content();
        String scopeManifest = context.requireInput("scope_manifest.md").content();
        String scenarioList = context.requireInput("scenario_list.md").content();

        Frontmatter fm = frontmatterCodec.parse(testPlan);
        JsonNode scopeFm = frontmatterCodec.parse(scopeManifest).metadata();
        JsonNode scenarioFm = scenarioList.stripLeading().startsWith(FrontmatterCodec.DELIMITER)
                ? frontmatterCodec.parse(scenarioList).metadata()
                : jsonMapper.createObjectNode();
        boolean ecsEnabled = scopeFm.path("ecs_enabled").asBoolean(false);

        JsonNode cases = fm.metadata().path("cases");
        if (!cases.isArray() || cases.isEmpty()) {
            log.info("No cases found in test_plan.md frontmatter — skipping TestRail sync");
            return AgentResult.of("test_plan.md", testPlan);
        }

        long sectionId = resolveSectionId(scopeFm, scenarioFm);
        if (sectionId == 0L) {
            log.info("TestRail section not configured (set FACTORY_CYPRESS_TESTRAIL_SECTION_ID or add testrail_section to scope_manifest.md frontmatter) — skipping TestRail sync");
            return AgentResult.of("test_plan.md", testPlan);
        }

        long cypressLabelId = resolveCypressLabel();
        Integer devTeamId = resolveDevTeamId(scopeFm.path("team").asString(""));

        Map<String, String> idMap = new HashMap<>();
        ArrayNode updatedCases = jsonMapper.createArrayNode();

        for (JsonNode tc : cases) {
            ObjectNode updated = (ObjectNode) tc.deepCopy();
            String tcId = tc.path("id").asString("");
            String title = tc.path("title").asString("");
            boolean tcEcs = ecsEnabled || tc.path("ecsEnabled").asBoolean(false);

            Map<String, Object> metadata = buildMetadata(tc, tcEcs, cypressLabelId, devTeamId);
            try {
                long caseId = testRail.addCaseWithMetadata(
                        sectionId, title, null, null, metadata);
                String cId = "C" + caseId;
                idMap.put(tcId, cId);
                updated.put("testrailId", cId);
                log.info("Created TestRail case {} for {}", cId, tcId);
            } catch (Exception e) {
                log.warn("Failed to create TestRail case for {} ({}): {}", tcId, title, e.getMessage());
                updated.put("testrailId", "");
            }
            updatedCases.add(updated);
        }

        ObjectNode updatedMeta = (ObjectNode) fm.metadata().deepCopy();
        updatedMeta.set("cases", updatedCases);

        String updatedBody = replaceIds(fm.body(), idMap);
        String updatedContent = frontmatterCodec.render(updatedMeta, updatedBody);
        return AgentResult.of("test_plan.md", updatedContent);
    }

    private long resolveSectionId(JsonNode scopeFm, JsonNode scenarioFm) {
        String override = scopeFm.path("testrail_section").asString("");
        if (!override.isBlank()) return parseOrResolveSectionRef(override.trim());
        String suggestion = scenarioFm.path("testrail_section").asString("");
        if (!suggestion.isBlank()) return parseOrResolveSectionRef(suggestion.trim());
        Long defaultId = properties.testrailSectionId();
        return defaultId != null ? defaultId : 0L;
    }

    private Integer resolveDevTeamId(String team) {
        if (team == null || team.isBlank()) return null;
        return TEAM_TO_DEV_TEAM_ID.get(team.toLowerCase().trim());
    }

    private long parseOrResolveSectionRef(String ref) {
        try {
            return Long.parseLong(ref);
        } catch (NumberFormatException ignored) {
        }
        try {
            return testRail.resolveSectionByPath(ref);
        } catch (Exception e) {
            throw new AgentExecutionException(
                    "Cannot resolve TestRail section '" + ref + "': " + e.getMessage(), e);
        }
    }

    private long resolveCypressLabel() {
        String label = properties.testrailLabel();
        try {
            return testRail.getOrCreateLabel(label);
        } catch (Exception e) {
            throw new AgentExecutionException(
                    "Cannot resolve '" + label + "' TestRail label — create it manually or check credentials: "
                            + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildMetadata(JsonNode tc, boolean ecsEnabled, long cypressLabelId,
                                               Integer devTeamId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("template_id", 2);
        meta.put("custom_automation_type", 2);
        meta.put("custom_ecs_enabled", ecsEnabled);
        meta.put("priority_id", properties.testrailPriorityId());
        if (properties.testrailCaseTypeId() != null) {
            meta.put("type_id", properties.testrailCaseTypeId());
        }
        meta.put("labels", List.of(cypressLabelId));
        if (devTeamId != null) {
            meta.put("custom_dev_team", devTeamId);
        }

        JsonNode preconds = tc.path("preconditions");
        if (preconds.isArray() && !preconds.isEmpty()) {
            meta.put("custom_preconds", toHtmlList(preconds));
        }

        meta.put("custom_steps_separated", buildSteps(tc.path("steps")));
        meta.put("custom_test_group", mapTier(tc.path("tier").asString("medium")));
        return meta;
    }

    private List<Map<String, Object>> buildSteps(JsonNode stepsNode) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!stepsNode.isArray()) {
            return result;
        }
        for (JsonNode step : stepsNode) {
            Map<String, Object> row = new HashMap<>();
            row.put("content", wrap(step.path("action").asString("")));
            row.put("expected", wrap(step.path("expected").asString("")));
            row.put("additional_info", "");
            row.put("refs", "");
            row.put("markdown_editor_id", 1);
            result.add(row);
        }
        return result;
    }

    private int mapTier(String tier) {
        return TIER_TO_TEST_GROUP.getOrDefault(tier.toLowerCase().replace(" ", ""), 2);
    }

    private String toHtmlList(JsonNode items) {
        StringBuilder sb = new StringBuilder("<ol>");
        for (JsonNode item : items) {
            sb.append("<li>").append(item.asString("")).append("</li>");
        }
        sb.append("</ol>");
        return sb.toString();
    }

    private static String wrap(String text) {
        return "<p>" + text + "</p>";
    }

    private String replaceIds(String body, Map<String, String> idMap) {
        if (idMap.isEmpty() || body == null) {
            return body;
        }
        String result = body;
        for (Map.Entry<String, String> entry : idMap.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
