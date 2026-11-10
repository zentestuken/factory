package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.testrail.TestRailCase;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.connectors.testrail.TestRailSectionRef;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoveragePrefetchTest {

    private static final CypressFlowProperties PROPS = new CypressFlowProperties("", "main", "", null, null,
            null, null, 3, null, null, true);

    /** Area section "Eureka › Users" holds C1; the story key is cited by C9 (filed elsewhere) and C1. */
    private static final class FakeTestRail implements TestRailConnector {
        final List<String> refQueries = new ArrayList<>();

        @Override public List<TestRailSectionRef> getSections() {
            return List.of(new TestRailSectionRef(10, "Eureka › Users"));
        }
        @Override public List<TestRailCase> getCases(long sectionId) {
            return List.of(new TestRailCase(1, "Area case", "", List.of()));
        }
        @Override public List<TestRailCase> getCasesByRefs(String ref) {
            refQueries.add(ref);
            return ref.equals("UIU-3562")
                    ? List.of(new TestRailCase(9, "Elsewhere but cites the story", "pre", List.of()),
                              new TestRailCase(1, "Area case", "", List.of()))
                    : List.of();
        }
        @Override public long addCase(long sectionId, String title, String steps, String expected) { return 0; }
        @Override public long addRun(String name, List<Long> caseIds) { return 0; }
        @Override public void addResults(long runId, Map<Long, Boolean> results, String comment) { }
        @Override public JsonNode getTestCaseDetails(String testCaseId) { return null; }
    }

    @Test
    void casesCitingTheStoryComeFirstAndAreNotDuplicated() {
        FakeTestRail testRail = new FakeTestRail();

        CoveragePrefetch.Coverage coverage = new CoveragePrefetch(testRail, PROPS)
                .load(List.of("eureka"), List.of("UIU-3562", "UXPROD-4760"));

        assertThat(testRail.refQueries).containsExactly("UIU-3562", "UXPROD-4760");
        assertThat(coverage.index()).startsWith("# Cases that already cite the story or a related ticket")
                .contains("**C9** — Elsewhere but cites the story _(refs: UIU-3562)_");
        assertThat(coverage.cases()).containsOnlyKeys(9L, 1L);
    }

    @Test
    void refsMatchesSurviveWhenNoAreaSectionMatches() {
        CoveragePrefetch.Coverage coverage = new CoveragePrefetch(new FakeTestRail(), PROPS)
                .load(List.of("inn-reach"), List.of("UIU-3562"));

        assertThat(coverage.cases()).containsKeys(9L);
        assertThat(coverage.index()).contains("No area-specific TestRail section found");
    }
}
