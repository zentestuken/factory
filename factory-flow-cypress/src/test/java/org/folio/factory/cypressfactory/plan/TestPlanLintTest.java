package org.folio.factory.cypressfactory.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules moved out of the reviewer prompt because they are mechanically decidable. Each test
 * that asserts a violation is paired with one asserting the shape the prompt explicitly allows -
 * a false positive here costs a whole regeneration, so the carve-outs matter as much as the catches.
 */
class TestPlanLintTest {

    private static TestPlanParser.ParsedCase caseWith(List<String> preconditions,
                                                      List<TestPlanParser.ParsedCase.StepRow> steps,
                                                      String automationNotes) {
        return new TestPlanParser.ParsedCase("TC001", "A title", "critical", false, true, false,
                preconditions, steps, automationNotes, null, "", List.of());
    }

    private static TestPlanParser.ParsedCase caseWithSteps(
            List<TestPlanParser.ParsedCase.StepRow> steps) {
        return caseWith(List.of("User is logged in"), steps, "");
    }

    private static TestPlanParser.ParsedCase.StepRow step(String action, String expected) {
        return new TestPlanParser.ParsedCase.StepRow(action, expected);
    }

    // ── R9: precondition ordinals ────────────────────────────────────────────

    @Test
    void flagsAPreconditionOrdinalThatCannotResolve() {
        // The real 2026-09-28 miss: dash-bulleted preconditions, steps citing "#2". Missed by the
        // reviewer on two consecutive attempts over unchanged text.
        List<String> findings = TestPlanLint.check(List.of(caseWith(
                List.of("User is logged in", "A match profile named <legacy> exists"),
                List.of(step("Open the match profile from Preconditions #2", "Detail view opens")),
                "")));

        assertThat(findings).singleElement().asString()
                .contains("TC001 [R9] Step 1")
                .contains("Preconditions #2")
                .contains("no numbered preconditions at all");
    }

    @Test
    void flagsAnOrdinalInAnExpectedResultToo() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Open the profile", "Matches the Instance from Preconditions #4")))));

        assertThat(findings).singleElement().asString().contains("[R9]").contains("#4");
    }

    @Test
    void doesNotFlagAStepThatNamesWhatItNeedsInsteadOfAnOrdinal() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Open the \"Legacy_ISBN_Match\" profile", "Detail view opens")))));

        assertThat(findings).isEmpty();
    }

    @Test
    void resolvesAnOrdinalAgainstGenuinelyNumberedPreconditions() {
        // Defensive: if the parser ever starts collecting numbered preconditions, a reference that
        // does resolve must stop being reported. Only the out-of-range one is a violation.
        List<String> findings = TestPlanLint.check(List.of(caseWith(
                List.of("1. User is logged in", "2. A profile exists"),
                List.of(step("Open the profile from Preconditions #2", "Opens"),
                        step("Open the thing from Preconditions #5", "Opens")),
                "")));

        assertThat(findings).singleElement().asString().contains("#5")
                .contains("only 2 numbered precondition(s)");
    }

    // ── Preconditions dropped by the parser ──────────────────────────────────

    @Test
    void flagsACaseWhosePreconditionsDidNotSurviveParsing() {
        // A numbered list is dropped entirely by the parser, so the reviewer sees preconditions in
        // the text and passes the case while TestRail would receive none. Only the parse shows it.
        List<String> findings = TestPlanLint.check(List.of(caseWith(
                List.of(), List.of(step("Click \"Save\"", "Toast appears")), "")));

        assertThat(findings).singleElement().asString()
                .contains("[preconditions]")
                .contains("dropped entirely");
    }

    // ── R5: framework syntax, and its carve-out ──────────────────────────────

    @Test
    void flagsFrameworkSyntaxInAStep() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Run cy.get('#save').click()", "Record is saved")))));

        assertThat(findings).singleElement().asString().contains("[R5]").contains("cy.");
    }

    @Test
    void neverFlagsInteractorNamesInTheAutomationNotesLine() {
        // The prompt is emphatic about this: Automation notes exist to name real helpers, and
        // reporting them is a false positive that costs a regeneration. Here it is structural -
        // the notes are a separate field, so the check cannot reach them.
        List<String> findings = TestPlanLint.check(List.of(caseWith(
                List.of("User is logged in"),
                List.of(step("Click \"Save & close\"", "Toast appears")),
                "Use eHoldingsPackage.js's edit(); assert via cy.get('#x').should('exist')")));

        assertThat(findings).isEmpty();
    }

    // ── R6: actor prefix ─────────────────────────────────────────────────────

    @Test
    void flagsAnActorPrefixedAction() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("User clicks the \"Save\" button", "Record is saved")))));

        assertThat(findings).singleElement().asString().contains("[R6]").contains("user clicks");
    }

    @Test
    void doesNotFlagAnImperativeActionThatMerelyMentionsAUser() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Select the user \"jdoe\" from the list", "User row is highlighted")))));

        assertThat(findings).isEmpty();
    }

    // ── R17: chained actions, and the three accepted split forms ─────────────

    @Test
    void flagsThreeActionsChainedInOneSentence() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Enter \"020\" in the \"Field\" field, select \"Barcode\" in the dropdown, "
                        + "and click \"Save as profile & Close\"", "Toast appears")))));

        assertThat(findings).singleElement().asString().contains("[R17]").contains("chains 3");
    }

    @Test
    void countsAnActionHiddenBehindAThenConnective() {
        // Verbatim from the 2026-09-30 UIDATIMP-1781 run, where the reviewer flagged 11 of these and
        // lint reported none: the last fragment starts with "then", so the verb went uncounted and
        // the chain looked two actions long.
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(step(
                "Fill \"Field\" = \"020\", \"In. 1\" = \"*\", \"Subfield\" = \"a\" in the "
                        + "\"Incoming MARC Bibliographic record\" section, select any value from the "
                        + "\"Existing Instance record field\" dropdown, then click "
                        + "\"Save as profile & Close\"",
                "Toast appears")))));

        assertThat(findings).singleElement().asString().contains("[R17]").contains("chains 3");
    }

    @Test
    void countsOtherLeadingConnectivesToo() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(step(
                "Click \"Actions\", next select \"Edit\", finally save the profile",
                "Profile is saved")))));

        assertThat(findings).singleElement().asString().contains("[R17]");
    }

    @Test
    void doesNotFlagAnActionAlreadySplitWithArrows() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Enter \"020\" in the \"Field\" field -> select \"Barcode\" -> click \"Save\"",
                        "Toast appears")))));

        assertThat(findings).isEmpty();
    }

    @Test
    void doesNotFlagAnActionAlreadySplitIntoBulletedSubActions() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("\n- Enter \"020\" in the \"Field\" field\n- Select \"Barcode\"\n- Click \"Save\"",
                        "Toast appears")))));

        assertThat(findings).isEmpty();
    }

    @Test
    void doesNotFlagTwoChainedActions() {
        // The rule is 3 or more; two is the normal, accepted shape.
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Enter \"020\" in the \"Field\" field and click \"Save\"", "Toast appears")))));

        assertThat(findings).isEmpty();
    }

    // ── Clean plan ───────────────────────────────────────────────────────────

    @Test
    void reportsNothingForACompliantCase() {
        List<String> findings = TestPlanLint.check(List.of(caseWith(
                List.of("User with the \"Settings - Manage\" Capability Set is logged in",
                        "User is on \"Settings\" > \"Data import\""),
                List.of(step("Click \"Actions\"", "Menu expands"),
                        step("Click \"New match profile\"", "\"New match profile\" page opens")),
                "Reuse matchOnVRN.js's createMatchProfileForVRN")));

        assertThat(findings).isEmpty();
    }

    @Test
    void checksEveryCaseNotJustTheFirst() {
        List<String> findings = TestPlanLint.check(List.of(
                caseWithSteps(List.of(step("Click \"Save\"", "Toast appears"))),
                caseWithSteps(List.of(step("User clicks \"Save\"", "Toast appears")))));

        assertThat(findings).singleElement().asString().contains("[R6]");
    }

    // ── R4: inline Field = value lists ───────────────────────────────────────

    @Test
    void flagsTwoFieldValuePairsJoinedInlineInAnExpectedResult() {
        // Both real violations on record backtick the value, which is why inline code spans are
        // not stripped before counting.
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("POST the match profile",
                        "Response status = `201 Created`; response body's "
                        + "`matchExpression.qualifier.comparisonPart` = `\"NUMERICS_ONLY\"`")))));

        assertThat(findings).singleElement().asString()
                .contains("TC001 [R4] Step 1")
                .contains("2 \"Field = value\" pairs inline");
    }

    @Test
    void countsEveryPairOnTheLine() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Open the job summary",
                        "Summary view shows an \"Instance\" row with \"Updated\" = 1, "
                        + "\"Created\" = 0, \"Discarded\" = 0, \"Error\" = 0")))));

        assertThat(findings).singleElement().asString().contains("[R4]").contains("4 \"Field = value\"");
    }

    @Test
    void doesNotFlagPairsAlreadySplitOneToABullet() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Open the profile", "The detail view shows:\n- Existing record = \"Instance\"\n"
                        + "- Incoming record = \"MARC Bibliographic\"\n- Field = \"020\"")))));

        assertThat(findings).isEmpty();
    }

    @Test
    void flagsABulletThatItselfJoinsTwoPairs() {
        // The per-line reading, which the prompt's "and no bullet list" wording would have missed:
        // the result IS bulleted, but one bullet still carries two pairs.
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Open the profile", "The detail view shows:\n"
                        + "- Existing record = \"Instance\", Incoming record = \"Static value\"\n"
                        + "- Field = \"020\"")))));

        assertThat(findings).singleElement().asString().contains("[R4]");
    }

    @Test
    void ignoresEqualsSignsInsideAnInlineCodeSpan() {
        // A backticked literal is one thing to type, not two assertions to bullet - however many
        // equals signs it contains. What decides it is which side of the backticks the operator is
        // on, not whether backticks are present.
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("POST the payload", "The request body contains "
                        + "`qualifierType = BEGINS_WITH, qualifierValue = n, "
                        + "comparisonPart = NUMERICS_ONLY`")))));

        assertThat(findings).isEmpty();
    }

    @Test
    void stillFlagsPairsWhoseOperatorSitsOutsideTheBackticks() {
        // The real run-7 TC004 violation: the values are backticked but the operators are not, so
        // these are two assertions and must not be exempted by the inline-code rule.
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("PUT the match profile",
                        "Response status = `200 OK`; response body still contains "
                        + "`comparisonPart` = `\"NUMERICS_ONLY\"`")))));

        assertThat(findings).singleElement().asString().contains("[R4]").contains("2 \"Field = value\"");
    }

    @Test
    void doesNotFlagASinglePairWhoseFieldAndValueAreBothBackticked() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Read the response", "`matchExpression.qualifier.comparisonPart` = `\"NUMERICS_ONLY\"`")))));

        assertThat(findings).isEmpty();
    }

    @Test
    void ignoresPairsInsideAFencedCodeBlock() {
        // A request body or MARC breaker line is a payload to type in, not a field list to bullet.
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("POST the payload below", "Request is accepted:\n```\nqualifierType = "
                        + "BEGINS_WITH\nqualifierValue = n\ncomparisonPart = NUMERICS_ONLY\n```")))));

        assertThat(findings).isEmpty();
    }

    @Test
    void doesNotFlagUnspacedEqualsSigns() {
        // The spacing requirement is the whole false-positive guard: query strings and MARC
        // breaker syntax never space the operator.
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Open the search URL",
                        "Results page opens for ?searchtype=advanced&qindex=title&sort=relevance")))));

        assertThat(findings).isEmpty();
    }

    @Test
    void doesNotFlagASingleFieldValuePair() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Open the profile", "Detail view shows Existing record = \"Instance\"")))));

        assertThat(findings).isEmpty();
    }

    // -- R3: relative value assertions -------------------------------------

    @Test
    void flagsTheUninflectedFormTheBannedPhraseListMisses() {
        // The only R3 violation a reviewer caught in 11 runs: "update accordingly", which is NOT on
        // the prompt's banned-phrase list ("updated accordingly" is). A literal list would miss it.
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Press Enter", "Search re-runs using \"Exact phrase\" and results update accordingly")))));

        assertThat(findings).singleElement().asString()
                .contains("TC001 [R3] Step 1")
                .contains("accordingly");
    }

    @Test
    void flagsItInsideABulletedExpectedResultToo() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Apply the filter", "The pane shows:\n- 12 results\n- Header result count updates accordingly")))));

        assertThat(findings).singleElement().asString().contains("[R3]");
    }

    @Test
    void doesNotFlagAnAbsoluteValueChange() {
        // R3's own exception: "from X to Y" states the final value, so it is not a relative
        // assertion. It needs no special guard here - it does not contain the word.
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Save the record", "\"Instance status term\" is updated from \"Temporary\" to \"Batch Loaded\"")))));

        assertThat(findings).isEmpty();
    }

    @Test
    void doesNotFlagAnActionThatMentionsItOutsideTheExpectedResult() {
        List<String> findings = TestPlanLint.check(List.of(caseWithSteps(List.of(
                step("Sort the list accordingly", "The \"Name\" column is sorted A to Z")))));

        assertThat(findings).isEmpty();
    }
}
