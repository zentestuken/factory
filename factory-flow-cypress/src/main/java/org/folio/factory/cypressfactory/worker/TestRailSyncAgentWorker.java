package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
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

/**
 * Syncs approved test cases to TestRail after Gate 1 approval.
 * Reads the approved test_plan.md, creates a TestRail case per automatable test case,
 * and writes back an updated test_plan.md with TestRail C###### IDs.
 */
public class TestRailSyncAgentWorker implements AgentWorker {

    public static final String ID = "cypress-testrail-sync";

    private static final Logger log = LoggerFactory.getLogger(TestRailSyncAgentWorker.class);

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

        Frontmatter fm = frontmatterCodec.parse(testPlan);
        JsonNode scopeFm = frontmatterCodec.parse(scopeManifest).metadata();

        String team = scopeFm.path("team").asString("");
        boolean ecsEnabled = scopeFm.path("ecs_enabled").asBoolean(false);

        JsonNode cases = fm.metadata().path("cases");
        if (!cases.isArray() || cases.isEmpty()) {
            log.info("No cases found in test_plan.md frontmatter — skipping TestRail sync");
            return AgentResult.of("test_plan.md", testPlan);
        }

        if (properties.testrailSectionId() == null) {
            log.info("FACTORY_CYPRESS_TESTRAIL_SECTION_ID not configured — skipping TestRail sync");
            return AgentResult.of("test_plan.md", testPlan);
        }

        Map<String, String> idMap = new HashMap<>();
        ArrayNode updatedCases = jsonMapper.createArrayNode();

        for (JsonNode tc : cases) {
            ObjectNode updated = (ObjectNode) jsonMapper.readTree(tc.toString());
            if (!tc.path("automatable").asBoolean(false)) {
                updatedCases.add(updated);
                continue;
            }

            String tcId = tc.path("id").asString("");
            String title = tc.path("title").asString("");

            Map<String, Object> metadata = buildCaseMetadata(tc, team, ecsEnabled);
            metadata.put("custom_steps_separated", buildStepsSeparated(tc.path("steps")));
            try {
                long caseId = testRail.addCaseWithMetadata(
                        properties.testrailSectionId(), title, "", "", metadata);
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

        ObjectNode updatedMeta = (ObjectNode) jsonMapper.readTree(fm.metadata().toString());
        updatedMeta.set("cases", updatedCases);

        String updatedBody = replaceIds(fm.body(), idMap);
        String updatedContent = frontmatterCodec.render(updatedMeta, updatedBody);
        return AgentResult.of("test_plan.md", updatedContent);
    }

    private List<Map<String, String>> buildStepsSeparated(JsonNode stepsNode) {
        List<Map<String, String>> result = new ArrayList<>();
        if (!stepsNode.isArray()) {
            return result;
        }
        for (JsonNode step : stepsNode) {
            Map<String, String> row = new HashMap<>();
            row.put("content", step.path("action").asString(""));
            row.put("expected", step.path("expected").asString(""));
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> buildCaseMetadata(JsonNode tc, String team, boolean ecsEnabled) {
        Map<String, Object> meta = new HashMap<>();
        // 2 = Automated in TestRail's default automation type field
        meta.put("custom_automation_type", 2);
        if (!team.isBlank()) {
            meta.put("custom_dev_team", team);
        }
        meta.put("custom_ecs_enabled", ecsEnabled || tc.path("ecsEnabled").asBoolean(false) ? 1 : 0);
        String tier = tc.path("tier").asString("medium");
        meta.put("custom_test_group", tier);
        return meta;
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
