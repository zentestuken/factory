package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.jira.JiraIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AreaDetectorTest {

    private final AreaDetector detector = new AreaDetector();

    private static JiraIssue issue(String key, String summary, String description) {
        return new JiraIssue(key, summary, description, "Open", "Story", List.of(), null);
    }

    // ── resolve(): validating what the router returned ───────────────────────

    @Test
    void keepsAKnownAreaFromTheRouter() {
        assertThat(detector.resolve(List.of("marc-authority"), issue("UIU-1", "x", "y")))
                .containsExactly("marc-authority");
    }

    @Test
    void normalisesCasingSpacesUnderscoresAndTheMdSuffix() {
        assertThat(detector.resolve(List.of("Bulk Edit"), issue("X-1", "", "")))
                .containsExactly("bulk-edit");
        assertThat(detector.resolve(List.of("bulk_edit"), issue("X-1", "", "")))
                .containsExactly("bulk-edit");
        assertThat(detector.resolve(List.of("marc-authority.md"), issue("X-1", "", "")))
                .containsExactly("marc-authority");
        assertThat(detector.resolve(List.of("references/context/finance.md"), issue("X-1", "", "")))
                .containsExactly("finance");
    }

    @Test
    void dropsAnUnknownAreaAndFallsBack() {
        // UIMARCAUTH resolves to marc-authority through the keyword fallback
        assertThat(detector.resolve(List.of("not-a-real-area"), issue("UIMARCAUTH-1", "", "")))
                .containsExactly("marc-authority");
    }

    @Test
    void keepsTheKnownAreaWhenOnlyOneOfTwoResolves() {
        assertThat(detector.resolve(List.of("finance", "invented-area"), issue("X-1", "", "")))
                .containsExactly("finance");
    }

    @Test
    void acceptsTwoAreasForACrossAreaStory() {
        assertThat(detector.resolve(List.of("orders", "finance"), issue("UIOR-1", "", "")))
                .containsExactly("orders", "finance");
    }

    @Test
    void truncatesToTwoAreas() {
        assertThat(detector.resolve(List.of("orders", "finance", "invoices"), issue("X-1", "", "")))
                .hasSize(2);
    }

    @Test
    void deduplicatesRepeatedAreas() {
        assertThat(detector.resolve(List.of("finance", "Finance"), issue("X-1", "", "")))
                .containsExactly("finance");
    }

    @Test
    void fallsBackWhenTheRouterReturnedNothing() {
        assertThat(detector.resolve(List.of(), issue("UIF-1", "", "")))
                .containsExactly("finance");
        assertThat(detector.resolve(null, issue("UIF-1", "", "")))
                .containsExactly("finance");
    }

    @Test
    void neverReturnsAnEmptyList() {
        assertThat(detector.resolve(List.of(), issue("ZZZZ-1", "", ""))).isNotEmpty();
    }

    // ── detectByKeywords(): the fallback path ────────────────────────────────

    @Test
    void detectsFromTheJiraProjectPrefix() {
        assertThat(detector.detectByKeywords(issue("UIMARCAUTH-553", "", "")))
                .containsExactly("marc-authority");
    }

    @Test
    void detectsFromStoryTextWhenThePrefixIsUnknown() {
        assertThat(detector.detectByKeywords(
                issue("ZZZZ-1", "Waive a fee/fine for a patron", "")))
                .contains("fees-fines");
    }

    @Test
    void returnsGenericWhenNothingMatches() {
        assertThat(detector.detectByKeywords(issue("ZZZZ-1", "aaa bbb", "ccc")))
                .containsExactly("generic");
    }

    @Test
    void toleratesANullIssue() {
        assertThat(detector.detectByKeywords(null)).containsExactly("generic");
    }

    // ── areaTable(): rendered into the router prompt ─────────────────────────

    @Test
    void areaTableListsEveryKnownArea() {
        String table = detector.areaTable();

        for (String area : AreaDetector.KNOWN_AREAS) {
            assertThat(table).describedAs("area %s must appear in the router table", area)
                    .contains("`" + area + "`");
        }
    }

    @Test
    void areaTableRendersAreasWithNoJiraPrefix() {
        // dcb has no PROJECT_KEY_AREAS entry - this used to throw on an immutable list
        assertThat(detector.areaTable()).contains("`dcb`");
    }

    @Test
    void areaTableCarriesPrefixesAndKeywords() {
        String table = detector.areaTable();

        assertThat(table).contains("UIMARCAUTH");
        assertThat(table).contains("quickmarc");
    }

    @Test
    void eurekaIsARoutableAreaAndOwnsTheRolesAndAuthPrefixes() {
        assertThat(detector.resolve(List.of("eureka"), issue("X-1", "", ""))).containsExactly("eureka");
        assertThat(detector.detectByKeywords(issue("UIROLES-202", "Show hidden capabilities", "")))
                .containsExactly("eureka");
        assertThat(detector.detectByKeywords(issue("MODUSERSKC-1", "", ""))).containsExactly("eureka");
        assertThat(detector.detectByKeywords(issue("ZZZZ-1", "Keycloak realm setup", "")))
                .containsExactly("eureka");
        assertThat(detector.areaTable()).contains("`eureka` | ").contains("UIROLES");
    }
}
