package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestRailSyncAgentWorkerTest {

    private final FrontmatterCodec codec = new FrontmatterCodec();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TestRailSyncAgentWorker worker(CapturingConnector connector, Long defaultSectionId) {
        CypressFlowProperties props = new CypressFlowProperties("", "main", "", defaultSectionId, null, null, null);
        return new TestRailSyncAgentWorker(connector, codec, props, jsonMapper);
    }

    private String testPlanWithOneCase() {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_enabled", false, "case_count", 1),
                "## TC-1: Test login\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** Yes\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n" +
                "1. **Action:** click\n" +
                "   **Expected:** done\n");
    }

    private String testPlanWithAmendedBodyTitle() {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_enabled", false, "case_count", 1),
                "## TC-1: New Title\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** Yes\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n" +
                "1. **Action:** click\n" +
                "   **Expected:** done\n");
    }

    private String testPlanWithSpecialChars() {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_enabled", false, "case_count", 1),
                "## TC-1: Test case\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** Yes\n" +
                "- **ECS:** No\n\n" +
                "**Preconditions**\n" +
                "- Role < Admin & Viewer\n\n" +
                "**Steps**\n" +
                "1. **Action:** Navigate to Orders > List\n" +
                "   **Expected:** Count < 5 items\n");
    }

    private String testPlanWithMultiLineExpected() {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_enabled", false, "case_count", 1),
                "## TC-1: Test toolbar\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** Yes\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n" +
                "1. **Action:** Save the record\n" +
                "   **Expected:** Action bar shows:\n" +
                "   - 'Cancel' button is enabled\n" +
                "   - 'Save & keep editing' button is disabled\n");
    }

    private String testPlanWithTestGroup(String testGroup) {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_enabled", false, "case_count", 1),
                "## TC-1: Test case\n\n" +
                "- **Test Group:** " + testGroup + "\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** No\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n" +
                "1. **Action:** click\n" +
                "   **Expected:** done\n");
    }

    private String testPlanWithUserJourney(boolean journey) {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_enabled", false, "case_count", 1),
                "## TC-1: Test case\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** " + (journey ? "Yes" : "No") + "\n" +
                "- **Automatable:** No\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n" +
                "1. **Action:** click\n" +
                "   **Expected:** done\n");
    }

    private String testPlanNoCases() {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_enabled", false, "case_count", 0),
                "# Cypress Test Plan — ERM-1\n");
    }

    private String scopeManifest(String testrailSection) {
        return scopeManifest(testrailSection, null);
    }

    private String scopeManifest(String testrailSection, String team) {
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("issue_key", "ERM-1");
        base.put("ecs_enabled", false);
        if (testrailSection != null) base.put("testrail_section", testrailSection);
        if (team != null) base.put("team", team);
        return codec.render(base, "## Analysis\n");
    }

    private String scenarioList(String testrailSection) {
        Map<String, Object> meta = testrailSection != null
                ? Map.of("testrail_section", testrailSection)
                : Map.of();
        return codec.render(meta, "# Scenarios\n");
    }

    private AgentContext contextWith(String testPlan, String scopeManifest, String scenarioList) {
        return new AgentContext(UUID.randomUUID(), "testrail-sync",
                Map.of(
                        "test_plan.md", new ArtifactContent("test_plan.md", 1, "text/markdown", testPlan),
                        "scope_manifest.md", new ArtifactContent("scope_manifest.md", 1, "text/markdown", scopeManifest),
                        "scenario_list.md", new ArtifactContent("scenario_list.md", 1, "text/markdown", scenarioList)
                ),
                null, Map.of(), List.of("test_plan.md"));
    }

    // -------------------------------------------------------------------------
    // Section resolution priority
    // -------------------------------------------------------------------------

    @Test
    void usesNumericSectionIdFromScenarioList() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, null).execute(
                contextWith(testPlanWithOneCase(), scopeManifest(null), scenarioList("200")));

        assertThat(connector.capturedSectionId).isEqualTo(200L);
    }

    @Test
    void scopeManifestSectionOverridesScenarioListSection() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, null).execute(
                contextWith(testPlanWithOneCase(), scopeManifest("300"), scenarioList("200")));

        assertThat(connector.capturedSectionId).isEqualTo(300L);
    }

    @Test
    void resolvesPathStringFromScenarioListViaConnector() {
        CapturingConnector connector = new CapturingConnector();
        connector.resolvedSectionId = 500L;
        worker(connector, null).execute(
                contextWith(testPlanWithOneCase(), scopeManifest(null), scenarioList("MARC \u203a MARC authority")));

        assertThat(connector.capturedSectionId).isEqualTo(500L);
    }

    @Test
    void scopeManifestPathStringOverridesScenarioListSection() {
        CapturingConnector connector = new CapturingConnector();
        connector.resolvedSectionId = 777L;
        worker(connector, null).execute(
                contextWith(testPlanWithOneCase(), scopeManifest("Inventory \u203a Search"), scenarioList("200")));

        assertThat(connector.capturedSectionId).isEqualTo(777L);
    }

    @Test
    void fallsBackToPropertiesDefaultWhenNeitherArtifactHasSection() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithOneCase(), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedSectionId).isEqualTo(114774L);
    }

    @Test
    void skipsTestrailSyncWhenNothingConfigured() {
        CapturingConnector connector = new CapturingConnector();
        String originalPlan = testPlanWithOneCase();

        AgentResult result = worker(connector, null).execute(
                contextWith(originalPlan, scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedSectionId).isEqualTo(-1L);
        assertThat(result.outputs().get("test_plan.md")).isEqualTo(originalPlan);
    }

    @Test
    void skipsTestrailSyncWhenNoCasesInTestPlan() {
        CapturingConnector connector = new CapturingConnector();

        AgentResult result = worker(connector, 114774L).execute(
                contextWith(testPlanNoCases(), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedSectionId).isEqualTo(-1L);
        assertThat(result.outputs().get("test_plan.md")).isEqualTo(testPlanNoCases());
    }

    @Test
    void toleratesScenarioListWithoutFrontmatter() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithOneCase(), scopeManifest(null), "# Scenarios\n\nplain markdown\n"));

        assertThat(connector.capturedSectionId).isEqualTo(114774L);
    }

    // -------------------------------------------------------------------------
    // Team → dev team ID mapping
    // -------------------------------------------------------------------------

    @Test
    void setsDevTeamIdFromKnownTeam() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithOneCase(), scopeManifest(null, "Folijet"), scenarioList(null)));

        assertThat(connector.capturedDevTeamId).isEqualTo(4);
    }

    @Test
    void setsDevTeamIdCaseInsensitive() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithOneCase(), scopeManifest(null, "EUREKA"), scenarioList(null)));

        assertThat(connector.capturedDevTeamId).isEqualTo(21);
    }

    @Test
    void omitsDevTeamIdWhenTeamUnknown() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithOneCase(), scopeManifest(null, "unknown-squad"), scenarioList(null)));

        assertThat(connector.capturedDevTeamId).isNull();
    }

    @Test
    void omitsDevTeamIdWhenTeamAbsent() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithOneCase(), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedDevTeamId).isNull();
    }

    // -------------------------------------------------------------------------
    // Refs field (Jira issue key)
    // -------------------------------------------------------------------------

    @Test
    void setsRefsToIssueKey() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithOneCase(), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedMetadata.get("refs")).isEqualTo("ERM-1");
    }

    // -------------------------------------------------------------------------
    // Title read from body heading (user edits body at HITL gate)
    // -------------------------------------------------------------------------

    @Test
    void titleIsReadFromBodyHeading() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithAmendedBodyTitle(), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedTitle).isEqualTo("New Title");
    }

    // -------------------------------------------------------------------------
    // HTML escaping (Bug: < and > in step text produce malformed HTML in TestRail)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void htmlEscapesSpecialCharsInStepsAndPreconditions() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithSpecialChars(), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedPreconds)
                .contains("&lt;").contains("&amp;")
                .doesNotContain("<li>Role < Admin");

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) connector.capturedMetadata.get("custom_steps_separated");
        assertThat(steps).hasSize(1);
        assertThat((String) steps.get(0).get("content")).isEqualTo("<p>Navigate to Orders &gt; List</p>");
        assertThat((String) steps.get(0).get("expected")).isEqualTo("<p>Count &lt; 5 items</p>");
    }

    // -------------------------------------------------------------------------
    // Multi-line expected/action text
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void parsesMultiLineExpectedResult() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithMultiLineExpected(), scopeManifest(null), scenarioList(null)));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) connector.capturedMetadata.get("custom_steps_separated");
        assertThat(steps).hasSize(1);
        assertThat((String) steps.get(0).get("content")).isEqualTo("<p>Save the record</p>");
        assertThat((String) steps.get(0).get("expected"))
                .isEqualTo("<p>Action bar shows:</p><ul><li>'Cancel' button is enabled</li>" +
                           "<li>'Save &amp; keep editing' button is disabled</li></ul>");
    }

    // -------------------------------------------------------------------------
    // testGroup → custom_test_group mapping
    // -------------------------------------------------------------------------

    @Test
    void mapsTestGroupSmokeToId1() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithTestGroup("smoke"), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedMetadata.get("custom_test_group")).isEqualTo(1);
    }

    @Test
    void mapsTestGroupCriticalToId2() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithTestGroup("critical"), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedMetadata.get("custom_test_group")).isEqualTo(2);
    }

    @Test
    void mapsTestGroupExtendedToId3() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithTestGroup("extended"), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedMetadata.get("custom_test_group")).isEqualTo(3);
    }

    @Test
    void unknownTestGroupDefaultsToCriticalPath() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithTestGroup("unknown-value"), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedMetadata.get("custom_test_group")).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // userJourney → custom_user_journey
    // -------------------------------------------------------------------------

    @Test
    void setsUserJourneyTrueWhenYes() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithUserJourney(true), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedMetadata.get("custom_user_journey")).isEqualTo(true);
    }

    @Test
    void setsUserJourneyFalseWhenNo() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithUserJourney(false), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedMetadata.get("custom_user_journey")).isEqualTo(false);
    }

    @Test
    void defaultsUserJourneyToFalseWhenFieldAbsent() {
        CapturingConnector connector = new CapturingConnector();
        // fixture has no User Journey line at all — use testPlanWithTestGroup which omits it
        // (actually it includes "No", but we test the default via the old Tier-style plan)
        String planNoJourneyLine = codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_enabled", false, "case_count", 1),
                "## TC-1: Test\n\n" +
                "- **Test Group:** critical\n" +
                "- **Automatable:** No\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n1. **Action:** click\n   **Expected:** done\n");
        worker(connector, 114774L).execute(
                contextWith(planNoJourneyLine, scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedMetadata.get("custom_user_journey")).isEqualTo(false);
    }

    // -------------------------------------------------------------------------
    // type_id default
    // -------------------------------------------------------------------------

    @Test
    void setsTypeIdDefaultFunctional() {
        CapturingConnector connector = new CapturingConnector();
        // props constructed with null typeId — CypressFlowProperties defaults it to 1L
        worker(connector, 114774L).execute(
                contextWith(testPlanWithOneCase(), scopeManifest(null), scenarioList(null)));

        assertThat(connector.capturedMetadata.get("type_id")).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // Stub connector
    // -------------------------------------------------------------------------

    private static class CapturingConnector implements TestRailConnector {
        long capturedSectionId = -1L;
        long resolvedSectionId = 0L;
        Integer capturedDevTeamId = null;
        String capturedTitle = null;
        String capturedPreconds = null;
        Map<String, Object> capturedMetadata = null;

        @Override
        public long addCase(long sectionId, String title, String steps, String expected) {
            return 0;
        }

        @Override
        public long addRun(String name, List<Long> caseIds) {
            return 0;
        }

        @Override
        public void addResults(long runId, Map<Long, Boolean> results, String comment) {
        }

        @Override
        public long addCaseWithMetadata(long sectionId, String title, String steps, String expected,
                                        Map<String, Object> metadata) {
            this.capturedSectionId = sectionId;
            this.capturedTitle = title;
            this.capturedMetadata = metadata;
            Object devTeam = metadata.get("custom_dev_team");
            this.capturedDevTeamId = devTeam instanceof Integer i ? i : null;
            Object preconds = metadata.get("custom_preconds");
            this.capturedPreconds = preconds instanceof String s ? s : null;
            return 999L;
        }

        @Override
        public long getOrCreateLabel(String title) {
            return 72L;
        }

        @Override
        public long resolveSectionByPath(String path) {
            return resolvedSectionId;
        }
    }
}
