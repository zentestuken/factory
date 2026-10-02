package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.UnconfiguredConnectors;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.cypressfactory.worker.SkillWorkspaceService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assessment gates enrichment, so a counter that returns the same number for every file
 * silently turns that gate into a constant — which is exactly what counting headings did.
 */
class ContextPrefetchAssessmentTest {

    private ContextPrefetch prefetch() {
        CypressFlowProperties props =
                new CypressFlowProperties("", "main", "", null, null, null, null, 3, null);
        return new ContextPrefetch(
                new SkillWorkspaceService(new UnconfiguredConnectors.GitHub(), props),
                new UnconfiguredConnectors.GitHub(), new UnconfiguredConnectors.Jira(),
                JsonMapper.builder().build(), props, "");
    }

    /** One heading, N numbered rules under it — the shape every real context file uses. */
    private static String rules(int count) {
        StringBuilder sb = new StringBuilder("## Key Business Rules for Test Cases\n\n");
        for (int i = 1; i <= count; i++) {
            sb.append(i).append(". Rule number ").append(i).append('\n');
        }
        return sb.toString();
    }

    private static String complete(int ruleCount) {
        return rules(ruleCount)
                + "\n## Exact UI Texts\n\n"
                + "- toast \"Saved successfully\"\n"
                + "- toast \"Record deleted successfully\"\n"
                + "- toast \"Updated successfully\"\n"
                + "\n## Known Gaps\n\n- one open question\n";
    }

    @Test
    void aCompleteFileHasNoShortfalls() {
        assertThat(prefetch().assess(complete(25))).isEmpty();
    }

    @Test
    void rulesAreCountedNotTheHeading() {
        assertThat(prefetch().assess(complete(4)))
                .anyMatch(s -> s.contains("4 key business rules"));
    }

    @Test
    void aMissingUiTextsSectionIsNamed() {
        assertThat(prefetch().assess(rules(25)))
                .anyMatch(s -> s.contains("Exact UI Texts"));
    }

    @Test
    void tooFewToastsIsNamed() {
        String oneToast = complete(25)
                .replace("- toast \"Record deleted successfully\"\n", "")
                .replace("- toast \"Updated successfully\"\n", "");
        assertThat(prefetch().assess(oneToast))
                .anyMatch(s -> s.contains("confirmed toasts"));
    }

    @Test
    void tooManyKnownGapsBlocksAnOtherwiseCompleteFile() {
        String gappy = complete(25).replace("- one open question\n",
                "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n");
        assertThat(prefetch().assess(gappy)).anyMatch(s -> s.contains("known gaps"));
    }

    @Test
    void thinContextMirrorsTheAssessment() {
        assertThat(prefetch().isThinContext(complete(25))).isFalse();
        assertThat(prefetch().isThinContext(rules(1))).isTrue();
    }
}
