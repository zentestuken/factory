package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.testrail.TestRailCase;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.connectors.testrail.TestRailSectionRef;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageLoaderWorkerTest {

    private final FrontmatterCodec codec = new FrontmatterCodec();

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CoverageLoaderWorker worker(TestRailConnector connector, Long fallbackSectionId) {
        CypressFlowProperties props = new CypressFlowProperties(
                "", "main", "", fallbackSectionId, null, null, null);
        return new CoverageLoaderWorker(connector, codec, props);
    }

    private AgentContext contextWith(String contextMd) {
        return new AgentContext(UUID.randomUUID(), "coverage-load",
                Map.of("context.md", new ArtifactContent("context.md", 1, "text/markdown", contextMd)),
                null, Map.of(), List.of("existing_coverage.md"));
    }

    private String contextMd(String... areas) {
        StringBuilder sb = new StringBuilder("---\nareas:\n");
        for (String area : areas) sb.append("  - ").append(area).append('\n');
        sb.append("---\n# Context\n");
        return sb.toString();
    }

    private static TestRailSectionRef sec(long id, String path) {
        return new TestRailSectionRef(id, path);
    }

    private static TestRailCase tc(long id, String title) {
        return new TestRailCase(id, title, "");
    }

    private static TestRailCase tcWithPrecon(long id, String title, String preconds) {
        return new TestRailCase(id, title, preconds);
    }

    /** Stub connector: returns fixed sections and fixed cases per section ID. */
    private static class StubConnector implements TestRailConnector {

        private final List<TestRailSectionRef> sections;
        private final Map<Long, List<TestRailCase>> casesBySection;
        private boolean getSectionsThrows;

        StubConnector(List<TestRailSectionRef> sections, Map<Long, List<TestRailCase>> casesBySection) {
            this.sections = sections;
            this.casesBySection = casesBySection;
        }

        @Override public long addCase(long s, String t, String st, String e) { return 0; }
        @Override public long addRun(String n, List<Long> ids) { return 0; }
        @Override public void addResults(long r, Map<Long, Boolean> res, String c) {}

        @Override
        public List<TestRailSectionRef> getSections() {
            if (getSectionsThrows) throw new RuntimeException("network error");
            return sections;
        }

        @Override
        public List<TestRailCase> getCases(long sectionId) {
            return casesBySection.getOrDefault(sectionId, List.of());
        }

        @Override
        public JsonNode getTestCaseDetails(String testCaseId) {
            return null;
        }
    }

    private static StubConnector stub(List<TestRailSectionRef> sections,
                                      Map<Long, List<TestRailCase>> casesBySection) {
        return new StubConnector(sections, casesBySection);
    }

    // -------------------------------------------------------------------------
    // Area detection
    // -------------------------------------------------------------------------

    @Test
    void genericAreaProducesEmptyCoverage() {
        StubConnector connector = stub(
                List.of(sec(10, "Inventory"), sec(20, "MARC")),
                Map.of());

        AgentResult result = worker(connector, null).execute(contextWith(contextMd("generic")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).contains("No existing coverage loaded");
    }

    @Test
    void noAreasProducesEmptyCoverage() {
        StubConnector connector = stub(List.of(sec(10, "Inventory")), Map.of());

        AgentResult result = worker(connector, null).execute(contextWith(contextMd()));

        assertThat(result.outputs().get("existing_coverage.md")).contains("No existing coverage loaded");
    }

    @Test
    void emptySectionsListProducesEmptyCoverage() {
        // TestRail not configured — UnconfiguredConnectors returns List.of()
        StubConnector connector = stub(List.of(), Map.of());

        AgentResult result = worker(connector, null).execute(contextWith(contextMd("inventory")));

        assertThat(result.outputs().get("existing_coverage.md")).contains("No existing coverage loaded");
    }

    @Test
    void testRailExceptionProducesEmptyCoverage() {
        StubConnector connector = stub(List.of(), Map.of());
        connector.getSectionsThrows = true;

        AgentResult result = worker(connector, null).execute(contextWith(contextMd("inventory")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).contains("No existing coverage loaded");
        assertThat(coverage).contains("TestRail unavailable");
    }

    // -------------------------------------------------------------------------
    // Section keyword matching
    // -------------------------------------------------------------------------

    @Test
    void matchesSectionByAreaKeywords() {
        StubConnector connector = stub(
                List.of(sec(10, "Inventory"), sec(20, "MARC › MARC authority"), sec(30, "Orders")),
                Map.of(20L, List.of(tc(1001, "Create authority record"))));

        AgentResult result = worker(connector, null).execute(contextWith(contextMd("marc-authority")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).contains("C1001");
        assertThat(coverage).contains("Create authority record");
        assertThat(coverage).doesNotContain("Inventory");
        assertThat(coverage).doesNotContain("Orders");
    }

    @Test
    void matchesHyphenatedAreaBothKeywordsRequiredInPath() {
        // "fees-fines" → keywords ["fees", "fines"] — section must contain both
        StubConnector connector = stub(
                List.of(sec(10, "Fees & Fines"), sec(20, "Fees Overview")),
                Map.of(
                        10L, List.of(tc(100, "Pay fine")),
                        20L, List.of(tc(200, "View fees summary"))));

        AgentResult result = worker(connector, null).execute(contextWith(contextMd("fees-fines")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).contains("C100");       // "Fees & Fines" → both keywords match
        assertThat(coverage).doesNotContain("C200");  // "Fees Overview" → "fines" missing → no match
    }

    @Test
    void multipleAreasMatchDifferentSections() {
        StubConnector connector = stub(
                List.of(sec(10, "Inventory"), sec(20, "MARC › MARC authority")),
                Map.of(
                        10L, List.of(tc(100, "Inventory case")),
                        20L, List.of(tc(200, "Authority case"))));

        AgentResult result = worker(connector, null)
                .execute(contextWith(contextMd("inventory", "marc-authority")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).contains("C100");
        assertThat(coverage).contains("C200");
    }

    @Test
    void noMatchingSectionsProducesEmptyCoverage() {
        // sections exist but none match "marc-authority"
        StubConnector connector = stub(
                List.of(sec(10, "Inventory"), sec(114774L, "test_ai")),
                Map.of(10L, List.of(tc(100, "Inventory case")),
                       114774L, List.of(tc(200, "Patron notice case"))));

        AgentResult result = worker(connector, 114774L)
                .execute(contextWith(contextMd("marc-authority")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).contains("No existing coverage loaded");
        assertThat(coverage).doesNotContain("C100");
        assertThat(coverage).doesNotContain("C200");
    }

    // -------------------------------------------------------------------------
    // Fallback section exclusion (requirement 2)
    // -------------------------------------------------------------------------

    @Test
    void fallbackSectionIsExcludedEvenWhenKeywordsMatch() {
        // Unlikely in practice, but: fallback section name happens to contain area keywords
        StubConnector connector = stub(
                List.of(sec(114774L, "marc-authority-test_ai"), sec(20, "MARC › MARC authority")),
                Map.of(
                        114774L, List.of(tc(999, "Unrelated case in test_ai")),
                        20L, List.of(tc(200, "Real authority case"))));

        AgentResult result = worker(connector, 114774L)
                .execute(contextWith(contextMd("marc-authority")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).doesNotContain("C999");  // fallback excluded
        assertThat(coverage).contains("C200");         // real section included
    }

    @Test
    void noMatchWhenOnlyFallbackMatchesShowsFallbackNameInMessage() {
        // "test_ai" doesn't match "marc-authority", so fallback name appears in the skip message
        StubConnector connector = stub(
                List.of(sec(114774L, "test_ai")),
                Map.of(114774L, List.of(tc(999, "Unrelated"))));

        AgentResult result = worker(connector, 114774L)
                .execute(contextWith(contextMd("marc-authority")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).contains("test_ai");        // fallback section named (req 3)
        assertThat(coverage).contains("114774");         // ID also mentioned
        assertThat(coverage).contains("No existing coverage loaded");
    }

    // -------------------------------------------------------------------------
    // Output content
    // -------------------------------------------------------------------------

    @Test
    void casesLabeledWithSectionPathInOutput() {
        StubConnector connector = stub(
                List.of(sec(20, "MARC › MARC authority")),
                Map.of(20L, List.of(tc(1001, "Create authority"))));

        AgentResult result = worker(connector, null).execute(contextWith(contextMd("marc-authority")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).contains("MARC › MARC authority");  // section path in header
        assertThat(coverage).contains("_(MARC › MARC authority)_");  // section label per case
    }

    @Test
    void casesDeduplicatedAcrossSections() {
        // Case ID 1001 appears in both sections — should appear only once
        StubConnector connector = stub(
                List.of(sec(10, "Inventory"), sec(11, "Inventory › Search")),
                Map.of(
                        10L, List.of(tc(1001, "Case in parent")),
                        11L, List.of(tc(1001, "Case in parent"), tc(1002, "Case in child"))));

        AgentResult result = worker(connector, null).execute(contextWith(contextMd("inventory")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).containsOnlyOnce("C1001");
        assertThat(coverage).contains("C1002");
    }

    @Test
    void permissionsExtractedFromPreconditions() {
        String preconds = "1. User with following Capability Sets is logged in:\n" +
                "   - Inventory: View, create, edit, delete\n" +
                "2. Instance exists\n";
        StubConnector connector = stub(
                List.of(sec(10, "Inventory")),
                Map.of(10L, List.of(tcWithPrecon(100, "Edit instance", preconds))));

        AgentResult result = worker(connector, null).execute(contextWith(contextMd("inventory")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).contains("## Permissions referenced in existing cases");
        assertThat(coverage).contains("Inventory: View, create, edit, delete");
    }

    @Test
    void sectionPathsListedInHeader() {
        StubConnector connector = stub(
                List.of(sec(10, "Inventory"), sec(11, "Inventory › Search")),
                Map.of(
                        10L, List.of(tc(100, "Case A")),
                        11L, List.of(tc(101, "Case B"))));

        AgentResult result = worker(connector, null).execute(contextWith(contextMd("inventory")));

        String coverage = result.outputs().get("existing_coverage.md");
        assertThat(coverage).contains("Inventory");
        assertThat(coverage).contains("Inventory › Search");
    }
}
