package org.folio.factory.cypressfactory.plan;

import org.folio.factory.cypressfactory.plan.TestPlanParser.ParsedCase;
import org.folio.factory.cypressfactory.plan.TestPlanParser.Problem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPlanParserTest {

    private final TestPlanParser parser = new TestPlanParser();

    private static final String ONE_CASE = """
            ## TC001: Browse authority records
            - **Test Group:** critical
            - **User Journey:** No
            - **Automatable:** Yes
            - **ECS:** No

            **Preconditions**
            - User is logged in

            **Steps**
            1. **Action:** Navigate to MARC authority
               **Expected:** Browse pane is displayed
            """;

    // ── Section scoping (G1) ─────────────────────────────────────────────────

    @Test
    void parsesOnlyTheTestCasesSection() {
        String plan = "# Analysis\n\nSome analysis.\n\n# Test Cases\n\n" + ONE_CASE + "\n# Review\n\nverdict\n";

        assertThat(parser.parse(plan)).hasSize(1);
    }

    @Test
    void aColonHeadingInAnalysisIsNotParsedAsACase() {
        String plan = """
                # Analysis

                ## Notes: coverage gaps
                - **Test Group:** critical

                **Steps**
                1. **Action:** this is prose, not a case
                   **Expected:** nothing

                # Test Cases

                """ + ONE_CASE + "\n# Review\n";

        List<ParsedCase> cases = parser.parse(plan);

        assertThat(cases).hasSize(1);
        assertThat(cases.getFirst().id()).isEqualTo("TC001");
    }

    @Test
    void aColonHeadingInReviewIsNotParsedAsACase() {
        String plan = "# Analysis\n\n# Test Cases\n\n" + ONE_CASE
                + "\n# Review\n\n## Remaining: two issues\n- **Test Group:** critical\n";

        assertThat(parser.parse(plan)).hasSize(1);
    }

    @Test
    void fallsBackToTheWholeBodyWhenTheHeadingIsAbsent() {
        assertThat(parser.parse(ONE_CASE)).hasSize(1);
    }

    @Test
    void returnsNothingForABlankBody() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    // ── Case content ─────────────────────────────────────────────────────────

    @Test
    void readsAttributesAndTitle() {
        ParsedCase c = parser.parse("# Test Cases\n\n" + ONE_CASE).getFirst();

        assertThat(c.id()).isEqualTo("TC001");
        assertThat(c.title()).isEqualTo("Browse authority records");
        assertThat(c.testGroup()).isEqualTo("critical");
        assertThat(c.userJourney()).isFalse();
        assertThat(c.automatable()).isTrue();
        assertThat(c.ecsEnabled()).isFalse();
    }

    @Test
    void readsMultipleCases() {
        String plan = "# Test Cases\n\n" + ONE_CASE + "\n"
                + ONE_CASE.replace("TC001", "TC002").replace("Browse authority records", "Second case");

        assertThat(parser.parse(plan)).extracting(ParsedCase::id).containsExactly("TC001", "TC002");
    }

    @Test
    void joinsMultiLineExpectedResults() {
        String plan = """
                # Test Cases

                ## TC001: Save a record
                - **Test Group:** critical

                **Steps**
                1. **Action:** Save the record
                   **Expected:** Action bar shows:
                   - 'Cancel' is enabled
                   - 'Save' is disabled
                """;

        String expected = parser.parse(plan).getFirst().steps().getFirst().expected();

        assertThat(expected).isEqualTo("Action bar shows:\n- 'Cancel' is enabled\n- 'Save' is disabled");
    }

    @Test
    void foldsNestedPreconditionsIntoTheirParent() {
        String plan = """
                # Test Cases

                ## TC001: Test
                - **Test Group:** critical

                **Preconditions**
                - User has Capability Sets:
                  - Data - View
                  - Data - Edit
                - User is on the app

                **Steps**
                1. **Action:** click
                   **Expected:** done
                """;

        List<String> preconds = parser.parse(plan).getFirst().preconditions();

        assertThat(preconds).hasSize(2);
        assertThat(preconds.getFirst())
                .isEqualTo("User has Capability Sets:\n- Data - View\n- Data - Edit");
    }

    @Test
    void readsAutomationNotesAndStopsTheStepList() {
        String plan = """
                # Test Cases

                ## TC001: Test
                - **Test Group:** critical

                **Steps**
                1. **Action:** click
                   **Expected:** done

                **Automation notes:** Use MarcAuthorities.searchBy()
                """;

        ParsedCase c = parser.parse(plan).getFirst();

        assertThat(c.steps()).hasSize(1);
        assertThat(c.automationNotes()).isEqualTo("Use MarcAuthorities.searchBy()");
    }

    // ── Validation (G3) ──────────────────────────────────────────────────────

    @Test
    void validatesACleanPlan() {
        assertThat(parser.validate(parser.parse("# Test Cases\n\n" + ONE_CASE))).isEmpty();
    }

    @Test
    void flagsAPlanWithNoCases() {
        List<Problem> problems = parser.validate(parser.parse("# Analysis\n\nnothing here\n\n# Review\n"));

        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst().detail()).contains("no test cases found");
    }

    @Test
    void flagsACaseWithNoSteps() {
        String plan = "# Test Cases\n\n## TC001: Test\n- **Test Group:** critical\n";

        List<Problem> problems = parser.validate(parser.parse(plan));

        assertThat(problems).extracting(Problem::caseId).containsExactly("TC001");
        assertThat(problems.getFirst().detail()).contains("no steps");
    }

    @Test
    void flagsABlankExpectedResult() {
        String plan = """
                # Test Cases

                ## TC001: Test
                - **Test Group:** critical

                **Steps**
                1. **Action:** click
                   **Expected:**
                """;

        assertThat(parser.validate(parser.parse(plan)))
                .anyMatch(p -> p.detail().contains("empty or placeholder expected result"));
    }

    @Test
    void flagsPlaceholderExpectedResults() {
        for (String placeholder : List.of("N/A", "TBD", "-", "TODO", "(none)")) {
            String plan = "# Test Cases\n\n## TC001: Test\n- **Test Group:** critical\n\n"
                    + "**Steps**\n1. **Action:** click\n   **Expected:** " + placeholder + "\n";

            assertThat(parser.validate(parser.parse(plan)))
                    .describedAs("placeholder '%s' must be rejected", placeholder)
                    .anyMatch(p -> p.detail().contains("empty or placeholder expected result"));
        }
    }

    @Test
    void reportsTheStepNumberOfTheOffendingStep() {
        String plan = """
                # Test Cases

                ## TC001: Test
                - **Test Group:** critical

                **Steps**
                1. **Action:** first
                   **Expected:** fine
                2. **Action:** second
                   **Expected:** N/A
                """;

        assertThat(parser.validate(parser.parse(plan)).getFirst().detail()).contains("step 2");
    }

    // ── casesSection ─────────────────────────────────────────────────────────

    @Test
    void casesSectionExcludesAnalysisAndReview() {
        String plan = "# Analysis\n\nanalysis body\n\n# Test Cases\n\n" + ONE_CASE + "\n# Review\n\nreview body\n";

        String section = parser.casesSection(plan);

        assertThat(section).contains("## TC001:");
        assertThat(section).doesNotContain("analysis body");
        assertThat(section).doesNotContain("review body");
    }
}
