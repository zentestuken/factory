package org.folio.factory.cypressfactory.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic checks for the checklist rules that are mechanically decidable, so they do not
 * depend on an LLM noticing them.
 *
 * <p><b>Why this exists.</b> The quality reviewer is asked to apply ~18 rules across every step of
 * every case in one pass. Two confirmed misses (2026-09-19 and 2026-09-28) were both on
 * <i>mechanical</i> rules over text that never changed between attempts - in one case a rule that
 * amounts to comparing two integers - missed on two consecutive attempts and caught on the third
 * with no budget left to fix it. That is a recall problem, and recall is not something prompt
 * wording fixes: a rule that can be decided by an algorithm gets 100% recall for free here, on
 * every attempt, at no token cost.</p>
 *
 * <p><b>What stays with the reviewer.</b> Only rules whose inputs are a closed set are moved here.
 * Rules that need judgment about meaning (is this string a UI label? are these two clauses really
 * separate facts? is this negative assertion redundant?) stay in the prompt, because code would
 * either miss them or fire on the wrong things. R17 is checked in both places on purpose: its verb
 * list is open-ended in the prompt, so this catches the common shapes reliably while the reviewer
 * can still recognise a verb this does not know.</p>
 *
 * <p>Findings are merged into the same feedback and retry budget as the LLM review - one plan, one
 * set of issues, one regeneration per failed attempt - and are formatted like the reviewer's own
 * output so the generator sees a single consistent list.</p>
 */
public final class TestPlanLint {

    /** R5 - framework syntax a human in a browser cannot execute. Closed list, from the prompt. */
    private static final List<String> FRAMEWORK_TOKENS = List.of(
            "cy.", ".should(", "@alias", "data-testid=", ".find(", ".click()", "expect(",
            "describe(", "it(", "beforeEach(");

    /** R6 - actor prefixes banned at the start of an action. Closed list, from the prompt. */
    private static final List<String> ACTOR_PREFIXES = List.of(
            "user clicks", "user navigates", "user selects", "user opens", "user enters",
            "user fills", "the user", "qa engineer", "tester");

    /** R9 - a step citing a precondition by ordinal. */
    private static final Pattern PRECONDITION_REF =
            Pattern.compile("Preconditions?\\s*#(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * R4 - a fenced code block is a real payload (a request body, a MARC breaker line), not an
     * inline field list, so it is removed before counting.
     */
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```.*?```");

    /**
     * R4 - an inline code span, replaced by a single non-space placeholder before pairs are counted.
     *
     * <p>What decides whether a {@code =} is a field list or a literal is <b>which side of the
     * backticks it sits on</b>, not whether backticks are present at all. A real violation puts the
     * operator outside the span and quotes only the value
     * ({@code Response status = `201 Created`}): that survives this substitution as
     * {@code Response status = X} and is still counted, which is what R4 required when it said
     * quoting or backticking the field names and values does not exempt them. A code literal puts
     * the operator inside the span ({@code `qualifierType = BEGINS_WITH, qualifierValue = n`}),
     * where it is one thing to type rather than two assertions to bullet, and collapses to
     * {@code X}.</p>
     *
     * <p>The placeholder is non-space on purpose: dropping the span outright would leave
     * {@code Response status = } with nothing after the operator and lose a genuine pair.</p>
     */
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*`");

    /**
     * R4 - one {@code Field = value} pair. The required spaces around {@code =} are the entire
     * guard against false positives, and they hold: they exclude query strings
     * ({@code ?searchtype=x}), MARC breaker lines and API payload syntax, none of which space the
     * operator. Measured over 11 real generation responses this flagged 5 lines, all 5 genuine
     * violations, and nothing at all on an attempt the reviewer had passed - which is why this one
     * moved to code while R14's semicolon clause (~25% precision) did not.
     */
    private static final Pattern FIELD_VALUE_PAIR = Pattern.compile("\\S = \\S");

    /**
     * R3 - the one shape of "relative value assertion" that actually occurs: a change described as
     * happening "accordingly" instead of naming the resulting value.
     *
     * <p>Deliberately NOT the prompt{@code s} banned-phrase list, which looks closed but is a
     * sample of an open family. Measured over 11 real generation responses, every other phrase on
     * it ("increased by", "decreased accordingly", "will be updated", "should now be
     * higher/lower") occurs exactly zero times, while the single violation a reviewer did catch was
     * "results update accordingly" - uninflected, and therefore absent from the list. A literal
     * implementation would have caught nothing and missed the only real case. This pattern finds
     * all three occurrences in the corpus, all three genuine.</p>
     *
     * <p>R3 stays in the reviewer prompt as well, like R17: code covers this shape, and the rule is
     * broader (an explicit delta with no absolute value is still a violation and is not matched
     * here). The prompt{@code s} "updated from X to Y" exception needs no guard, because that form
     * does not contain this word.</p>
     */
    private static final Pattern RELATIVE_ASSERTION = Pattern.compile("(?i)\\baccordingly\\b");

    /** A precondition written as a numbered list item, which is what an ordinal could resolve to. */
    private static final Pattern NUMBERED_PRECONDITION = Pattern.compile("^\\d+\\.\\s");

    /**
     * R17 - imperative verbs that start a distinct action.
     *
     * <p><b>This is a floor, not a grammar.</b> Almost any verb can head an action in some test, so
     * a fixed list can never be complete - and that is fine, because R17 is deliberately checked in
     * both places: this catches the shapes that recur, and the reviewer still sees the rule and can
     * recognise a verb that isn't here. Under-flagging costs nothing; over-flagging would cost a
     * whole regeneration, so observation verbs ("observe", "review", "confirm") are left out even
     * though they can appear in a run-on chain.</p>
     *
     * <p>Extended 2026-09-30 after a real run: {@code Select X, replace the query with Y, click
     * Search} counted only 2 because "replace" was missing, so six instances in one case went
     * uncaught while the reviewer flagged all of them.</p>
     */
    private static final Set<String> ACTION_VERBS = Set.of(
            "click", "enter", "select", "open", "expand", "check", "navigate", "fill", "drag",
            "uncheck", "collapse", "choose", "type", "upload", "clear", "close", "press",
            "replace", "set", "switch", "change", "keep", "submit", "save", "search", "apply",
            "sort", "remove", "delete", "add", "create", "scroll", "hover", "toggle", "deselect",
            "pick", "input", "paste", "drop", "reorder", "rename", "duplicate");

    /** Structure that already separates chained actions, per R17's own carve-out. */
    private static final Pattern ACTION_ALREADY_SPLIT = Pattern.compile("->|\\n\\s*-\\s|\\n\\s*\\d+[.)]\\s");

    /**
     * Words that introduce the next action in a chain without being the verb. Stripped before the
     * verb check, because "…, then click X" would otherwise be read as the non-verb "then" and the
     * action would go uncounted. Found on the 2026-09-30 UIDATIMP-1781 run, where the reviewer
     * flagged 11 R17 violations and lint reported none: every one of them chained with
     * {@code commas/"then"}, so the final verb was always hidden behind the connective.
     */
    private static final Pattern LEADING_CONNECTIVE =
            Pattern.compile("^(?:then|next|finally|after that|afterwards|also|now|lastly)\\s+");

    private TestPlanLint() {
    }

    /**
     * @return one pre-rendered finding per violation, in the reviewer's own line format, or an
     *         empty list when everything mechanically checkable passes
     */
    public static List<String> check(List<TestPlanParser.ParsedCase> cases) {
        List<String> findings = new ArrayList<>();
        for (TestPlanParser.ParsedCase c : cases) {
            checkPreconditionsSurvivedParsing(c, findings);
            int numbered = (int) c.preconditions().stream()
                    .filter(p -> NUMBERED_PRECONDITION.matcher(p).find())
                    .count();
            for (int i = 0; i < c.steps().size(); i++) {
                TestPlanParser.ParsedCase.StepRow step = c.steps().get(i);
                int number = i + 1;
                checkPreconditionReferences(c, step, number, numbered, findings);
                checkFrameworkTokens(c, step, number, findings);
                checkActorPrefix(c, step, number, findings);
                checkChainedActions(c, step, number, findings);
                checkInlineFieldList(c, step, number, findings);
                checkRelativeAssertion(c, step, number, findings);
            }
        }
        return findings;
    }

    /**
     * Not a checklist rule - something the reviewer structurally cannot see. The case-block format
     * takes preconditions as {@code - } bullets and {@link TestPlanParser} only collects those, but
     * the upstream skill text tells the model to number them instead. A numbered list is therefore
     * dropped silently: the reviewer reads the rendered text, sees preconditions, and passes the
     * case, while what actually reaches TestRail has none at all. Comparing the parse against the
     * text is the only way to catch that, so it belongs here rather than in the prompt.
     */
    private static void checkPreconditionsSurvivedParsing(TestPlanParser.ParsedCase c,
                                                         List<String> findings) {
        if (c.preconditions().isEmpty()) {
            findings.add(c.id() + " [preconditions] no preconditions were parsed from this case - "
                    + "every precondition must be a top-level \"- \" bullet under "
                    + "\"**Preconditions**\" (a numbered \"1.\" list is not recognised and is "
                    + "dropped entirely, so the case would reach TestRail with none)");
        }
    }

    /** R9 - an ordinal reference that cannot resolve to a numbered precondition. */
    private static void checkPreconditionReferences(TestPlanParser.ParsedCase c,
                                                   TestPlanParser.ParsedCase.StepRow step,
                                                   int number, int numberedPreconditions,
                                                   List<String> findings) {
        for (String text : List.of(step.action(), step.expected())) {
            Matcher m = PRECONDITION_REF.matcher(nullSafe(text));
            while (m.find()) {
                int referenced = Integer.parseInt(m.group(1));
                if (referenced > numberedPreconditions) {
                    findings.add(c.id() + " [R9] Step " + number + " references \"" + m.group()
                            + "\" but this case has " + (numberedPreconditions == 0
                                    ? "no numbered preconditions at all (the case-block format uses "
                                            + "\"- \" bullets, so an ordinal cannot resolve - describe "
                                            + "the precondition instead of citing its number)"
                                    : "only " + numberedPreconditions + " numbered precondition(s)"));
                }
            }
        }
    }

    /**
     * R5 - scoped to action and expected text only, which is exactly the prompt's carve-out: the
     * {@code **Automation notes:**} and {@code Automatable} lines are addressed to an automation
     * engineer and are <i>required</i> to name real helpers. Those live in separate fields on the
     * parsed case, so they are structurally out of reach here rather than relying on a reminder.
     */
    private static void checkFrameworkTokens(TestPlanParser.ParsedCase c,
                                             TestPlanParser.ParsedCase.StepRow step,
                                             int number, List<String> findings) {
        for (String token : FRAMEWORK_TOKENS) {
            for (String text : List.of(step.action(), step.expected())) {
                if (nullSafe(text).contains(token)) {
                    findings.add(c.id() + " [R5] Step " + number + " contains automation framework "
                            + "syntax \"" + token + "\" in the action or expected result - a manual "
                            + "executor cannot run it");
                    return;
                }
            }
        }
    }

    /** R6 - imperative style, no actor prefix. */
    private static void checkActorPrefix(TestPlanParser.ParsedCase c,
                                        TestPlanParser.ParsedCase.StepRow step,
                                        int number, List<String> findings) {
        String action = nullSafe(step.action()).stripLeading().toLowerCase(Locale.ROOT);
        for (String prefix : ACTOR_PREFIXES) {
            if (action.startsWith(prefix)) {
                findings.add(c.id() + " [R6] Step " + number + " action starts with the actor prefix "
                        + "\"" + prefix + "\" - use imperative style with no actor");
                return;
            }
        }
    }

    /**
     * R17 - 3 or more imperative actions run together with no separating structure. Counted only
     * across comma/"and" boundaries, and skipped entirely when the action already uses any of the
     * three forms the prompt accepts, so an action that is correctly split is never flagged.
     */
    private static void checkChainedActions(TestPlanParser.ParsedCase c,
                                            TestPlanParser.ParsedCase.StepRow step,
                                            int number, List<String> findings) {
        String action = nullSafe(step.action());
        if (action.isBlank() || ACTION_ALREADY_SPLIT.matcher(action).find()) {
            return;
        }
        int verbs = 0;
        for (String fragment : action.split(",|\\band\\b")) {
            String first = LEADING_CONNECTIVE
                    .matcher(fragment.strip().toLowerCase(Locale.ROOT)).replaceFirst("")
                    .split("\\s+", 2)[0]
                    .replaceAll("[^a-z]", "");
            if (ACTION_VERBS.contains(first)) verbs++;
        }
        if (verbs >= 3) {
            findings.add(c.id() + " [R17] Step " + number + " action chains " + verbs
                    + " actions in one run-on sentence with no separators - split into separate "
                    + "steps, \"->\"-separated, or bulleted sub-actions");
        }
    }

    /**
     * R4 - 2 or more {@code Field = value} pairs joined inline in one expected result.
     *
     * <p>Counted <b>per line</b> rather than per expected result, which is stricter than the
     * prompt's own "contains two or more occurrences of ` = ` and no bullet list" wording in the
     * one way that matters: a correctly bulleted result has one pair per bullet, so it never trips
     * this, while a bullet that itself joins two pairs still does. That is the same recursive
     * reading R14 spells out, and the whole-result form would have let it through.</p>
     */
    private static void checkInlineFieldList(TestPlanParser.ParsedCase c,
                                             TestPlanParser.ParsedCase.StepRow step,
                                             int number, List<String> findings) {
        String expected = FENCED_BLOCK.matcher(nullSafe(step.expected())).replaceAll("");
        expected = INLINE_CODE.matcher(expected).replaceAll("X");
        for (String line : expected.split("\\R")) {
            int pairs = 0;
            Matcher m = FIELD_VALUE_PAIR.matcher(line);
            while (m.find()) pairs++;
            if (pairs >= 2) {
                findings.add(c.id() + " [R4] Step " + number + " expected result joins " + pairs
                        + " \"Field = value\" pairs inline on one line - use a lead-in line plus a "
                        + "\"- Field = value\" bullet per pair");
                return;
            }
        }
    }

    /** R3 - a value change asserted as happening "accordingly" rather than as its final value. */
    private static void checkRelativeAssertion(TestPlanParser.ParsedCase c,
                                               TestPlanParser.ParsedCase.StepRow step,
                                               int number, List<String> findings) {
        Matcher m = RELATIVE_ASSERTION.matcher(nullSafe(step.expected()));
        if (m.find()) {
            findings.add(c.id() + " [R3] Step " + number + " expected result says something happens "
                    + "\"accordingly\" instead of stating the resulting value - assert the absolute "
                    + "value that should be on screen");
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
