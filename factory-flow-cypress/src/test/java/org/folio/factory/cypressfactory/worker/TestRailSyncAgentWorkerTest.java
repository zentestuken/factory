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
                Map.of("cases", List.of(
                        Map.of("id", "TC-1", "title", "Test login", "tier", "critical",
                                "steps", List.of(Map.of("action", "click", "expected", "done")))
                )),
                "## Test Plan\n");
    }

    private String testPlanNoCases() {
        return codec.render(Map.of("cases", List.of()), "## Plan\n");
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
    // Stub connector
    // -------------------------------------------------------------------------

    private static class CapturingConnector implements TestRailConnector {
        long capturedSectionId = -1L;
        long resolvedSectionId = 0L;
        Integer capturedDevTeamId = null;

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
            Object devTeam = metadata.get("custom_dev_team");
            this.capturedDevTeamId = devTeam instanceof Integer i ? i : null;
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
