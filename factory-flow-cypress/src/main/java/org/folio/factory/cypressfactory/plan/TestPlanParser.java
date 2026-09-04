package org.folio.factory.cypressfactory.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code # Test Cases} section of {@code test_plan.md}.
 *
 * <p>One parser, two callers: the generator runs it as a validator on its own output, and
 * TestRail sync runs it as the parser. If a plan parses at generation time it parses at
 * sync time by construction, so sync can never silently receive zero cases.</p>
 *
 * <p><b>Section scoping matters.</b> The plan body also holds {@code # Analysis} and
 * {@code # Review}. A heading like {@code ## Notes: coverage} matches {@link #CASE_HEADING}
 * and would otherwise be parsed as a test case, so parsing is restricted to the slice
 * between {@code # Test Cases} and the next top-level heading.</p>
 */
public class TestPlanParser {

    public static final String CASES_HEADING = "# Test Cases";

    private static final Pattern CASE_HEADING = Pattern.compile("^## (.+?):\\s+(.+)$");
    private static final Pattern ATTR_LINE = Pattern.compile("^- \\*\\*(.+?):\\*\\*\\s*(.*)$");
    private static final Pattern STEP_ACTION = Pattern.compile("^\\d+\\.\\s+\\*\\*Action:\\*\\*\\s*(.*)$");
    private static final Pattern STEP_EXPECTED = Pattern.compile("^\\*\\*Expected:\\*\\*\\s*(.*)$");
    private static final Pattern TOP_LEVEL_HEADING = Pattern.compile("(?m)^# ");

    private static final Set<String> PLACEHOLDER_EXPECTED =
            Set.of("n/a", "-", "—", "tbd", "todo", "(none)", "(see above)", "none");

    public record ParsedCase(
            String id, String title, String testGroup, boolean userJourney, boolean automatable,
            boolean ecsEnabled, List<String> preconditions, List<StepRow> steps, String automationNotes) {

        public record StepRow(String action, String expected) {}
    }

    /** A structural defect that would make the plan unusable for TestRail sync. */
    public record Problem(String caseId, String detail) {}

    private enum ParseState { ATTRS, PRECONDITIONS, STEPS, DONE }

    /**
     * Extracts the {@code # Test Cases} slice. When the heading is absent the whole body is
     * used, so a plan that predates the three-section envelope still parses.
     */
    public String casesSection(String body) {
        if (body == null || body.isBlank()) return "";
        int start = body.indexOf(CASES_HEADING);
        if (start < 0) return body;
        int afterHeading = start + CASES_HEADING.length();
        Matcher next = TOP_LEVEL_HEADING.matcher(body);
        int end = body.length();
        if (next.find(afterHeading)) {
            end = next.start();
        }
        return body.substring(afterHeading, end);
    }

    public List<ParsedCase> parse(String body) {
        String section = casesSection(body);
        if (section.isBlank()) return List.of();

        List<ParsedCase> result = new ArrayList<>();
        for (String block : section.split("(?m)^(?=## )")) {
            String[] lines = block.split("\n", -1);
            if (lines.length == 0) continue;
            Matcher heading = CASE_HEADING.matcher(lines[0].strip());
            if (!heading.matches()) continue;

            String caseId = heading.group(1).strip();
            String title = heading.group(2).strip();
            String testGroup = "";   // blank = attribute absent; the fallback is applied downstream, and reported
            boolean userJourney = false;
            boolean automatable = false;
            boolean ecsEnabled = false;
            List<String> preconditions = new ArrayList<>();
            List<ParsedCase.StepRow> steps = new ArrayList<>();
            String automationNotes = "";
            String pendingAction = null;
            StringBuilder pendingExpected = null;
            ParseState state = ParseState.ATTRS;

            for (int i = 1; i < lines.length; i++) {
                String stripped = lines[i].strip();
                if (stripped.isEmpty()) continue;

                switch (state) {
                    case ATTRS -> {
                        if (stripped.startsWith("**Preconditions**")) {
                            state = ParseState.PRECONDITIONS;
                        } else if (stripped.startsWith("**Steps**")) {
                            state = ParseState.STEPS;
                        } else {
                            Matcher attr = ATTR_LINE.matcher(stripped);
                            if (attr.matches()) {
                                switch (attr.group(1).toLowerCase()) {
                                    case "test group" -> testGroup = attr.group(2).strip();
                                    case "user journey" -> userJourney = attr.group(2).strip().equalsIgnoreCase("yes");
                                    case "automatable" -> automatable = attr.group(2).strip().equalsIgnoreCase("yes");
                                    case "ecs" -> ecsEnabled = attr.group(2).strip().equalsIgnoreCase("yes");
                                    default -> { /* unknown attribute - ignore */ }
                                }
                            }
                        }
                    }
                    case PRECONDITIONS -> {
                        if (stripped.startsWith("**Steps**")) {
                            state = ParseState.STEPS;
                        } else if (lines[i].startsWith("  - ")) {
                            if (!preconditions.isEmpty()) {
                                String last = preconditions.remove(preconditions.size() - 1);
                                preconditions.add(last + "\n- " + stripped.substring(2).strip());
                            }
                        } else if (stripped.startsWith("- ")) {
                            preconditions.add(stripped.substring(2).strip());
                        }
                    }
                    case STEPS -> {
                        if (stripped.startsWith("**Automation notes:**")) {
                            flushPendingStep(steps, pendingAction, pendingExpected);
                            pendingAction = null;
                            pendingExpected = null;
                            automationNotes = stripped.replaceFirst("^\\*\\*Automation notes:\\*\\*\\s*", "");
                            state = ParseState.DONE;
                        } else {
                            Matcher actionMatcher = STEP_ACTION.matcher(stripped);
                            Matcher expectedMatcher = STEP_EXPECTED.matcher(stripped);
                            if (actionMatcher.matches()) {
                                flushPendingStep(steps, pendingAction, pendingExpected);
                                pendingAction = actionMatcher.group(1).strip();
                                pendingExpected = null;
                            } else if (expectedMatcher.matches() && pendingAction != null) {
                                pendingExpected = new StringBuilder(expectedMatcher.group(1).strip());
                            } else if (pendingExpected != null
                                    && (stripped.startsWith("- ") || !stripped.startsWith("**"))) {
                                pendingExpected.append("\n").append(stripped);
                            } else if (pendingAction != null && pendingExpected == null
                                    && (stripped.startsWith("- ") || !stripped.startsWith("**"))) {
                                pendingAction = pendingAction + "\n" + stripped;
                            }
                        }
                    }
                    case DONE -> { /* nothing */ }
                }
            }
            flushPendingStep(steps, pendingAction, pendingExpected);
            result.add(new ParsedCase(caseId, title, testGroup, userJourney, automatable, ecsEnabled,
                    preconditions, steps, automationNotes));
        }
        return result;
    }

    /**
     * Structural defects only — content quality is the reviewer agent's job. An empty list
     * means the plan is safe to hand to TestRail sync.
     */
    public List<Problem> validate(List<ParsedCase> cases) {
        List<Problem> problems = new ArrayList<>();
        if (cases.isEmpty()) {
            problems.add(new Problem("-", "no test cases found under the '" + CASES_HEADING
                    + "' heading - check the '## TC001: Title' case-block format"));
            return problems;
        }
        for (ParsedCase c : cases) {
            if (c.title().isBlank()) {
                problems.add(new Problem(c.id(), "empty title"));
            }
            if (c.steps().isEmpty()) {
                problems.add(new Problem(c.id(), "no steps - each step must be "
                        + "'1. **Action:** ...' followed by '**Expected:** ...'"));
                continue;
            }
            for (int i = 0; i < c.steps().size(); i++) {
                String expected = c.steps().get(i).expected();
                if (expected == null || expected.isBlank()
                        || PLACEHOLDER_EXPECTED.contains(expected.strip().toLowerCase())) {
                    problems.add(new Problem(c.id(),
                            "step " + (i + 1) + " has an empty or placeholder expected result"));
                }
            }
        }
        return problems;
    }

    private static void flushPendingStep(List<ParsedCase.StepRow> steps, String action, StringBuilder expected) {
        if (action == null) return;
        steps.add(new ParsedCase.StepRow(action, expected != null ? expected.toString() : ""));
    }
}
