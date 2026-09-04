package org.folio.factory.cypressfactory.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevTeamCatalogTest {

    private final DevTeamCatalog catalog = new DevTeamCatalog();

    @Test
    void loadsEveryTeamFromTheBundledTable() {
        assertThat(catalog.all()).hasSize(27);
        assertThat(catalog.all()).allSatisfy(team -> {
            assertThat(team.jiraName()).isNotBlank();
            assertThat(team.jiraId()).isNotNull();
        });
    }

    @Test
    void mapsTeamNameToTheTestRailOptionId() {
        assertThat(catalog.testRailId("Promin")).contains(27);
        assertThat(catalog.testRailId("Thunderjet")).contains(8);
        assertThat(catalog.testRailId("Fenrir")).contains(29);
    }

    @Test
    void lookupToleratesCasingAndATrailingTeamSuffix() {
        // Jira values arrive with inconsistent casing and are sometimes decorated; the old
        // hardcoded map required an exact lowercase match and silently dropped anything else.
        assertThat(catalog.testRailId("promin")).contains(27);
        assertThat(catalog.testRailId("PROMIN")).contains(27);
        assertThat(catalog.testRailId("Promin Team")).contains(27);
        assertThat(catalog.testRailId("  Promin  ")).contains(27);
    }

    @Test
    void indexesTheTestRailSpellingWhereItDiffersFromJira() {
        // Jira says "Bama", TestRail says "BAMA"; a value from either system must resolve.
        assertThat(catalog.testRailId("BAMA")).contains(14);
        assertThat(catalog.testRailId("Bama")).contains(14);
        assertThat(catalog.canonicalName("BAMA")).isEqualTo("Bama");
    }

    @Test
    void canonicalNameNormalisesToTheJiraSpelling() {
        assertThat(catalog.canonicalName("promin team")).isEqualTo("Promin");
        assertThat(catalog.canonicalName("k-int")).isEqualTo("K-Int");
        assertThat(catalog.canonicalName("big fc")).isEqualTo("Big FC");
    }

    @Test
    void unknownTeamIsLeftUntouchedRatherThanGuessed() {
        assertThat(catalog.find("Nonesuch")).isEmpty();
        assertThat(catalog.testRailId("Nonesuch")).isEmpty();
        assertThat(catalog.canonicalName("Nonesuch")).isEqualTo("Nonesuch");
        assertThat(catalog.canonicalName(null)).isEmpty();
    }

    @Test
    void aRecognisedTeamWithNoTestRailIdIsStillFound() {
        // The case that matters when a team is added to Jira before it exists in TestRail —
        // "recognised but not syncable" must be distinguishable from "unknown".
        DevTeamCatalog partial = new DevTeamCatalog("""
                | Jira name | Jira ID | TestRail name | TestRail ID |
                |---|---|---|---|
                | Newteam | 12345 | Newteam | |
                """);

        assertThat(partial.find("Newteam")).isPresent();
        assertThat(partial.find("Newteam").orElseThrow().syncable()).isFalse();
        assertThat(partial.testRailId("Newteam")).isEmpty();
    }

    @Test
    void nameListRendersEveryTeamForPromptTemplating() {
        String names = catalog.nameList();

        assertThat(names).contains("Promin").contains("Thunderjet").contains("Fenrir");
        assertThat(names.split(", ")).hasSize(27);
    }
}
