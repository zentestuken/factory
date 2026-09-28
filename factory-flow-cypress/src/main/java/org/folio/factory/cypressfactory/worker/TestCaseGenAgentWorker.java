package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.agents.llm.AbstractLlmAgentWorker.TokenUsage;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.cypressfactory.plan.DevTeamCatalog;
import org.folio.factory.cypressfactory.plan.MarcFixtureBuilder;
import org.folio.factory.cypressfactory.plan.TestPlanParser;
import org.folio.factory.cypressfactory.prefetch.AreaDetector;
import org.folio.factory.cypressfactory.prefetch.ContextPrefetch;
import org.folio.factory.cypressfactory.prefetch.CoveragePrefetch;
import org.folio.factory.cypressfactory.prefetch.ExamplesPrefetch;
import org.folio.factory.cypressfactory.prefetch.FragmentPrefetch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicCacheTtl;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.ChatOptions;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single LLM worker of the Cypress flow: it runs MQA's {@code write-testrail-cases}
 * skill end to end in one session.
 *
 * <p>Phases (see CYPRESS_FLOW_PLAN.md Part I §2):</p>
 * <ol>
 *   <li><b>Fetch &amp; route</b> — raw Jira issue, then one small LLM call for the
 *       genuinely judgment-based decision SKILL.md step 1 describes: which area
 *       context file(s) to load. {@code refs} (SKILL.md step 3) is a per-case decision made
 *       during Generate instead, since only the generation call knows which linked tickets a
 *       given case actually verifies.</li>
 *   <li><b>Prefetch</b> — context file(s) + enrichment, examples, TAF fragments, existing
 *       TestRail coverage, section list. Deterministic; nothing throws.</li>
 *   <li><b>Generate</b> — one call carrying SKILL.md, the examples, the context and the
 *       raw ticket. Free-form markdown; no JSON schema.</li>
 *   <li><b>Validate</b> — parse own output with the same parser TestRail sync uses.</li>
 *   <li><b>Review loop</b> — automated quality review, up to {@code maxReviewIterations}.</li>
 *   <li><b>Assemble</b> — worker-owned frontmatter, then {@code test_plan.md}.</li>
 * </ol>
 */
public class TestCaseGenAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-testcase-gen-agent";

    private static final String ROUTER_PROMPT_ID = "cypress-area-router";
    private static final String GEN_PROMPT_ID = "cypress-testcase-gen";
    private static final String REVIEWER_PROMPT_ID = "cypress-testcase-reviewer";
    private static final String TRIAGE_PROMPT_ID = "cypress-coverage-triage";

    /** Read in full per run. Triage is told to over-select; this is the hard ceiling. */
    private static final int MAX_COVERAGE_CANDIDATES = 15;

    /**
     * The reviewer answers in two lines but reasons over every case first, and on a reasoning
     * model that reasoning is billed against the output ceiling. At the generation ceiling it
     * could spend the whole budget thinking and return no text at all - a call that looks
     * successful and yields nothing.
     */
    private static final int REVIEWER_MAX_TOKENS = 32768;

    private static final Logger log = LoggerFactory.getLogger(TestCaseGenAgentWorker.class);

    private static final Pattern JIRA_KEY = Pattern.compile("[A-Z][A-Z0-9]*-\\d+");
    private static final Pattern ROUTER_AREAS = Pattern.compile("(?m)^\\s*AREAS:\\s*(.+)$");
    private static final Pattern ROUTER_WHY = Pattern.compile("(?m)^\\s*WHY:\\s*(.+)$");
    private static final Pattern TRIAGE_CANDIDATES = Pattern.compile("(?m)^\\s*CANDIDATES:\\s*(.+)$");
    private static final Pattern CASE_ID = Pattern.compile("C?(\\d{1,12})");

    /** `**Automation notes:**` lines, which legitimately carry helper and interactor names. */
    /**
     * `## Scenarios` / `## Coverage matrix`, and the numbered scenario lines under the former.
     * Also accepts the model writing the label as bold text (`**Scenarios:**`) instead of a
     * real heading - a real, observed drift that must not be treated as "section missing", since
     * that silently skips the completeness cross-check below rather than actually running it.
     */
    private static final Pattern SCENARIOS_HEADING = Pattern.compile(
            "(?mi)^(?:##+[ ]*Scenario(?:s| analysis)|\\*\\*Scenario(?:s| analysis):?\\*\\*)[ ]*$");
    private static final Pattern MATRIX_HEADING =
            Pattern.compile("(?mi)^##+[ ]*Coverage matrix[ ]*$");
    private static final Pattern SCENARIO_ITEM =
            Pattern.compile("(?m)^[ \\t]*(\\d{1,2})[.)][ ]+\\S");
    /** An `## Xxx` heading with nothing but blank lines or a stray `---` under it. */
    private static final Pattern EMPTY_HEADING = Pattern.compile(
            "(?m)^##+[ ][^\\n]+\\R(?:[ \\t]*\\R)*(?:-{3,}[ \\t]*\\R(?:[ \\t]*\\R)*)?(?=##|#[ ]|\\z)");

    private static final Pattern AUTOMATION_NOTES =
            Pattern.compile("(?mi)^[ \\t]*\\*+Automation notes:\\*+.*(?:\\R|$)");
    /**
     * The fenced breaker-text/CSV payload under `**Attachment content**`. Stripped before
     * review for the same reason automation notes are: it is syntax, not case-text prose, and
     * R1-R18 would false-positive on it (R4's `= ` count, R12's quoting rule, ...). The
     * `- **Attachment:** kind=...` attribute line is left in place so R19 can still judge
     * whether an attachment is warranted at all, without seeing the raw payload.
     */
    private static final Pattern ATTACHMENT_CONTENT_BLOCK =
            Pattern.compile("(?ms)^\\*\\*Attachment content\\*\\*\\R+```.*?```\\R?");
    private static final Pattern ANALYSIS_TEAM = Pattern.compile("(?mi)^\\s*\\**team\\**:\\s*(.+?)\\s*$");
    private static final Pattern ANALYSIS_ECS_STORY = Pattern.compile("(?mi)^\\s*\\**ecs_story\\**:\\s*(\\w[\\w-]*)");
    /** The three delta-4 fields, once promoted to frontmatter, are removed from the prose. */
    private static final Pattern PROMOTED_FIELD = Pattern.compile(
            "(?mi)^[ \\t]*\\**(?:testrail_section|team|ecs_story)\\**[ \\t]*:.*(?:\\R|$)");
    /** A `---` fence pair left empty once its fields were stripped. */
    private static final Pattern EMPTY_FENCE =
            Pattern.compile("(?m)^---[ \\t]*\\R(?:[ \\t]*\\R)*^---[ \\t]*\\R");
    private static final Pattern BLANK_RUN = Pattern.compile("(?:[ \\t]*\\R){3,}");

    private static final Pattern ANALYSIS_SECTION =
            Pattern.compile("(?mi)^\\s*\\**testrail_section\\**:\\s*[\"']?(.+?)[\"']?\\s*$");
    /** {@code epam-<team>} / {@code epam_<team>} style Jira labels used to tag a story's team. */
    private static final Pattern EPAM_TEAM_LABEL = Pattern.compile("(?i)^epam[-_](.+)$");

    private final JiraConnector jira;
    private final TestRailConnector testRail;
    private final SkillWorkspaceService skills;
    private final AreaDetector areaDetector;
    private final ContextPrefetch contextPrefetch;
    private final FragmentPrefetch fragmentPrefetch;
    private final CoveragePrefetch coveragePrefetch;
    private final ExamplesPrefetch examplesPrefetch;
    private final FrontmatterCodec frontmatterCodec;
    private final TestPlanParser parser;
    private final DevTeamCatalog devTeams;
    private final PromptDump promptDump;
    private final CypressFlowProperties properties;
    private final String model;
    private final String jiraBaseUrl;
    private final int maxReviewIterations;
    private final boolean reviewDoubleCheck;

    public TestCaseGenAgentWorker(ChatClient chatClient, JiraConnector jira, TestRailConnector testRail,
                                  SkillWorkspaceService skills, AreaDetector areaDetector,
                                  ContextPrefetch contextPrefetch, FragmentPrefetch fragmentPrefetch,
                                  CoveragePrefetch coveragePrefetch, ExamplesPrefetch examplesPrefetch,
                                  FrontmatterCodec frontmatterCodec, TestPlanParser parser,
                                  DevTeamCatalog devTeams, PromptDump promptDump,
                                  CypressFlowProperties properties, String model, String jiraBaseUrl) {
        super(chatClient);
        this.jira = jira;
        this.testRail = testRail;
        this.skills = skills;
        this.areaDetector = areaDetector;
        this.contextPrefetch = contextPrefetch;
        this.fragmentPrefetch = fragmentPrefetch;
        this.coveragePrefetch = coveragePrefetch;
        this.examplesPrefetch = examplesPrefetch;
        this.frontmatterCodec = frontmatterCodec;
        this.parser = parser;
        this.devTeams = devTeams;
        this.promptDump = promptDump;
        this.properties = properties;
        this.model = model == null ? "" : model;
        this.jiraBaseUrl = jiraBaseUrl == null ? "" : jiraBaseUrl.strip();
        this.maxReviewIterations = properties.maxReviewIterations();
        this.reviewDoubleCheck = Boolean.TRUE.equals(properties.reviewDoubleCheck());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected Integer maxTokens() {
        return 32768;
    }

    /**
     * Caches the system prompt, which carries SKILL.md and the golden examples — roughly 25k
     * tokens that are byte-identical across every generation attempt and every run. The review
     * loop re-sends it up to three times per run, so the within-run saving alone pays for the
     * write; the five-minute TTL is a sliding window refreshed on each read, so it survives a
     * whole run regardless of how long the run takes.
     *
     * <p>The router and reviewer prompts also flow through here but their system prompts fall
     * below the cacheable minimum, so {@code CacheEligibilityResolver} skips them.</p>
     *
     * <p>Anthropic-specific by design. Under {@code FACTORY_LLM_PROVIDER=openai} the model
     * reads only the portable options, logs a warning for the rest, and simply does not
     * cache — {@code maxTokens} still applies.</p>
     */
    @Override
    protected ChatOptions.Builder<?> chatOptions() {
        return chatOptions(null);
    }

    /**
     * No explicit {@code effort} override for the reviewer - every model runs its own default.
     * Removed 2026-09-24: this used to force {@code effort: low} on Opus tier and above, on the
     * theory that a reasoning model could burn its whole output ceiling thinking about a
     * checklist scan and return no verdict at all. Never confirmed to actually happen (the only
     * place that failure mode fires is a deliberately-scripted test, not a production incident),
     * never exercised by the deployed model (Sonnet was never in the allow-list), and the loop
     * already degrades safely if it ever does happen - an empty/unparseable verdict stops the
     * loop and ships the plan with a note to review manually (see the {@code issueCount == 0}
     * branch in {@link #execute}), independent of this mechanism. Unvalidated complexity with a
     * safety net that already existed elsewhere - removed rather than kept "just in case."
     */
    @Override
    protected ChatOptions.Builder<?> chatOptions(String promptId) {
        boolean reviewing = REVIEWER_PROMPT_ID.equals(promptId);
        return AnthropicChatOptions.builder()
                .maxTokens(reviewing ? REVIEWER_MAX_TOKENS : maxTokens())
                .cacheOptions(AnthropicCacheOptions.builder()
                        .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                        .messageTypeTtl(MessageType.SYSTEM, AnthropicCacheTtl.FIVE_MINUTES)
                        .build());
    }

    @Override
    public AgentResult execute(AgentContext context) {
        TokenUsage[] totalUsage = {TokenUsage.ZERO};
        PromptDump.Run dump = promptDump.forExecution(context.executionId());

        // ── A · Fetch & route ────────────────────────────────────────────────
        JsonNode trigger = context.triggerPayload();
        String issueKey = requireIssueKey(trigger);
        JiraIssue issue = fetchIssue(issueKey, trigger);
        List<JiraIssue> linked = mergeUrlReferencedIssues(issue, fetchLinkedIssues(issueKey));

        // SKILL.md is the whole methodology - refuse to generate without it.
        String skill = skills.requireSkill();

        RouterResult route = route(issue, linked, dump);
        totalUsage[0] = totalUsage[0].plus(drainUsage());
        List<String> areas = areaDetector.resolve(route.areas(), issue);
        log.info("[{}] Area(s) {} · {}", ID, areas, route.why());

        // ── B · Prefetch ─────────────────────────────────────────────────────
        ContextPrefetch.ContextBundle ctx = contextPrefetch.load(areas, issue);
        ExamplesPrefetch.Examples examples = examplesPrefetch.load();
        FragmentPrefetch.Fragments fragments = fragmentPrefetch.scan(areas, issue.summary());
        CoveragePrefetch.Coverage coverage = coveragePrefetch.load(areas);
        String coverageBlock = coverage.index()
                + triageCoverage(coverage, issue, dump, totalUsage);
        String sections = buildSectionsBlock();

        ArtifactContent previousPlan = context.inputs().get("test_plan.md");
        ArtifactContent hitlFeedback = context.inputs().get("hitl_feedback.md");

        Map<String, Object> vars = new HashMap<>();
        vars.put("skill", skill);
        vars.put("examples", examples.text());
        vars.put("marc_examples", MarcFixtureBuilder.renderGoldenExamples());
        vars.put("context_files", ctx.text());
        vars.put("enrichment", buildProvenanceBlock(route, areas, ctx, examples, fragments, skill));
        vars.put("issue", renderIssue(issue));
        vars.put("dev_teams", devTeams.nameList());
        vars.put("linked_issues", renderLinkedIssues(linked));
        vars.put("fragments", fragments.text());
        vars.put("coverage", coverageBlock);
        vars.put("sections", sections);
        vars.put("hitl_feedback_block", buildHitlFeedbackBlock(hitlFeedback));
        vars.put("previous_plan_block", buildPreviousPlanBlock(previousPlan));
        vars.put("review_feedback", "");

        // ── C/D/E · Generate, validate, review ───────────────────────────────
        String plan;
        List<TestPlanParser.ParsedCase> cases;
        ReviewFeedback feedback;
        int attempt = 0;

        if (maxReviewIterations == 0) {
            log.info("[{}] Generating test cases (agent review disabled)", ID);
            plan = generateAndValidate(vars, totalUsage, dump, 1);
            cases = parser.parse(plan);
            feedback = new ReviewFeedback(true, "",
                    "Agent review disabled (FACTORY_CYPRESS_MAX_REVIEW_ITERATIONS=0)");
        } else {
            do {
                attempt++;
                log.info("[{}] Generating test cases, attempt {}/{}", ID, attempt, maxReviewIterations);
                plan = generateAndValidate(vars, totalUsage, dump, attempt);
                cases = parser.parse(plan);
                log.info("[{}] Generated {} test case(s), running quality review...", ID, cases.size());

                feedback = reviewTestCases(forReview(parser.casesSection(plan)),
                        renderIssue(issue), attempt, dump);
                totalUsage[0] = totalUsage[0].plus(drainUsage());
                List<String> attachmentErrors = validateAttachments(cases);
                if (!attachmentErrors.isEmpty()) {
                    log.warn("[{}] Attachment content failed deterministic validation on attempt {}/{}: {}",
                            ID, attempt, maxReviewIterations, attachmentErrors);
                    feedback = mergeAttachmentErrors(feedback, attachmentErrors);
                }
                if (feedback.passed()) {
                    log.info("[{}] Quality review PASSED on attempt {}/{}", ID, attempt, maxReviewIterations);
                    break;
                }
                long issueCount = feedback.issuesBlock().lines()
                        .filter(l -> l.strip().startsWith("-")).count();
                if (issueCount == 0) {
                    // Nothing actionable came back, so a re-generation would be told to fix an
                    // empty list and would rewrite blind - strictly worse than keeping this plan.
                    log.warn("[{}] Quality review on attempt {}/{} returned no verdict and no "
                            + "issues - keeping this plan and stopping the loop. The reviewer "
                            + "response was empty or unparseable; see the gate for the raw text.",
                            ID, attempt, maxReviewIterations);
                    feedback = new ReviewFeedback(false, "",
                            "The automated reviewer returned no usable response on attempt "
                            + attempt + " of " + maxReviewIterations + ", so these cases were not "
                            + "checked against the quality rules. Review them manually.");
                    break;
                }
                log.info("[{}] Quality review FAILED on attempt {}/{} ({} issue(s)) - retrying",
                        ID, attempt, maxReviewIterations, issueCount);
                String prior = (String) vars.get("review_feedback");
                String block = buildFeedbackBlock(feedback.issuesBlock(), attempt);
                vars.put("review_feedback", prior.isBlank() ? block : prior + "\n\n" + block);
                vars.put("previous_plan_block", buildPreviousAttemptSection(plan, attempt));
            } while (attempt < maxReviewIterations);

            if (!feedback.passed()) {
                log.warn("[{}] Quality gate exhausted after {}/{} attempt(s) - passing to HITL gate",
                        ID, attempt, maxReviewIterations);
            }
        }

        // ── F · Assemble ─────────────────────────────────────────────────────
        String analysis = sectionOf(plan, "# Analysis");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issue_key", issueKey);
        metadata.put("refs", String.join(", ", unionRefs(issueKey, cases, properties.refsExcludedProjects())));
        metadata.put("team", resolveTeam(analysis, issue));
        metadata.put("ecs_story", resolveEcsStory(analysis));
        metadata.put("testrail_section", resolveSection(analysis));
        metadata.put("case_count", cases.size());
        metadata.put("review_passed", feedback.passed());
        metadata.put("review_iterations", attempt);

        String cleaned = stripPromotedFields(
                replaceReviewSection(plan, renderReviewSection(feedback, attempt)));
        List<String> notes = new ArrayList<>(testGroupNotes(cases));
        notes.addAll(coverageMatrixNotes(cleaned));
        notes.addAll(ctx.truncationNotes());
        String body = appendFactoryNotes(cleaned, notes);

        Map<String, Object> metrics = !totalUsage[0].isEmpty()
                ? totalUsage[0].toMetrics()
                : Map.of();
        return new AgentResult(Map.of("test_plan.md", frontmatterCodec.render(metadata, body)), metrics);
    }

    /**
     * Drops the three delta-4 fields from the prose once they have been promoted to the
     * artifact's frontmatter. Models reliably emit them, but tend to wrap them in `---`
     * separators, which renders as a stray second frontmatter block duplicating what the real
     * one already says. Scoped to the text above {@code # Test Cases} so nothing inside a case
     * block can match.
     */
    static String stripPromotedFields(String body) {
        int cases = body.indexOf(TestPlanParser.CASES_HEADING);
        String head = cases < 0 ? body : body.substring(0, cases);
        String tail = cases < 0 ? "" : body.substring(cases);
        String stripped = PROMOTED_FIELD.matcher(head).replaceAll("");
        stripped = EMPTY_FENCE.matcher(stripped).replaceAll("");
        // Removing the delta-4 lines can leave their heading behind with nothing under it
        // ("## Metadata fields" followed by a stray `---`), which ships to every reviewer.
        stripped = EMPTY_HEADING.matcher(stripped).replaceAll("");
        return BLANK_RUN.matcher(stripped).replaceAll("\n\n") + tail;
    }

    /**
     * Names every case whose {@code Test Group} the factory had to fall back on, so a reviewer
     * at the gate sees it instead of discovering it in TestRail. Two silent defaults used to
     * stack here: a missing attribute became "critical" in the parser, and an unrecognised value
     * became Critical Path again at sync time.
     */
    static List<String> testGroupNotes(List<TestPlanParser.ParsedCase> cases) {
        List<String> missing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (TestPlanParser.ParsedCase c : cases) {
            if (c.testGroup().isBlank()) {
                missing.add(c.id());
            } else if (!TestRailSyncAgentWorker.isKnownTier(c.testGroup())) {
                unknown.add(c.id() + " (\"" + c.testGroup() + "\")");
            }
        }
        List<String> notes = new ArrayList<>();
        if (!missing.isEmpty()) {
            notes.add("No `Test Group` on " + String.join(", ", missing)
                    + " - defaulted to Critical Path. Set it explicitly if that is wrong.");
        }
        if (!unknown.isEmpty()) {
            notes.add("Unrecognised `Test Group` on " + String.join(", ", unknown)
                    + " - defaulted to Critical Path. Valid values: Smoke, Critical Path, Extended.");
        }
        return notes;
    }

    /**
     * Checks that every numbered scenario is accounted for in the coverage matrix. A scenario
     * silently folded into another case reads as lost coverage - the plan says three scenarios
     * and two cases, and the reviewer has to diff the two lists by hand to find out which.
     */
    static List<String> coverageMatrixNotes(String body) {
        Matcher scenarios = SCENARIOS_HEADING.matcher(body);
        if (!scenarios.find()) {
            // A regeneration that replaced `# Analysis` with a fix summary drops the scenarios
            // and the matrix together, so an absent section is not the same as "nothing to check".
            return List.of("No `## Scenarios` section in `# Analysis` - the scenario analysis and "
                    + "coverage matrix are missing. On a re-generated plan this usually means the "
                    + "analysis was replaced by a summary of the fixes.");
        }

        int start = scenarios.end();
        int end = nextHeading(body, start);
        List<String> numbers = new ArrayList<>();
        Matcher item = SCENARIO_ITEM.matcher(body.substring(start, end));
        while (item.find()) {
            if (!numbers.contains(item.group(1))) numbers.add(item.group(1));
        }
        if (numbers.isEmpty()) return List.of();

        Matcher matrix = MATRIX_HEADING.matcher(body);
        if (!matrix.find()) {
            return List.of("No `## Coverage matrix` for " + numbers.size()
                    + " numbered scenario(s). Each scenario should name the case that covers it, "
                    + "the case it was merged into, or why it is not covered.");
        }
        String matrixText = body.substring(matrix.end(), nextHeading(body, matrix.end()));
        List<String> unaccounted = numbers.stream()
                .filter(n -> !Pattern.compile("(?m)^[^\\S\\r\\n]*[|]?[^\\S\\r\\n]*" + n + "[.)]")
                        .matcher(matrixText).find())
                .toList();
        return unaccounted.isEmpty() ? List.of()
                : List.of("Scenario(s) " + String.join(", ", unaccounted)
                        + " are not in the coverage matrix - confirm they were merged, already "
                        + "covered, or deliberately skipped.");
    }

    private static int nextHeading(String body, int from) {
        Matcher m = Pattern.compile("(?m)^#{1,3}[ ]").matcher(body);
        return m.find(from) ? m.start() : body.length();
    }

    /** Appends factory-side observations to the end of `# Analysis`, above `# Test Cases`. */
    static String appendFactoryNotes(String body, List<String> notes) {
        if (notes.isEmpty()) return body;
        StringBuilder block = new StringBuilder("\n## Factory notes\n\n");
        for (String note : notes) block.append("- ").append(note).append('\n');

        int cases = body.indexOf(TestPlanParser.CASES_HEADING);
        if (cases < 0) return body.stripTrailing() + "\n" + block;
        return body.substring(0, cases).stripTrailing() + "\n" + block + "\n" + body.substring(cases);
    }

    // ── A · Fetch & route ────────────────────────────────────────────────────

    private static String requireIssueKey(JsonNode trigger) {
        String key = trigger == null ? "" : trigger.path("issueKey").asString("").strip();
        if (key.isBlank()) {
            throw new AgentExecutionException(ID + " requires 'issueKey' in the trigger payload");
        }
        return key;
    }

    /** Inline trigger payload wins when present; that is the zero-credentials path. */
    private JiraIssue fetchIssue(String issueKey, JsonNode trigger) {
        JsonNode inline = trigger.path("issue");
        if (!inline.isMissingNode() && !inline.isNull()) {
            JsonNode fields = inline.path("fields");
            List<String> labels = new ArrayList<>();
            for (JsonNode l : fields.path("labels")) labels.add(l.asString(""));
            return new JiraIssue(
                    inline.path("key").asString(issueKey),
                    fields.path("summary").asString(""),
                    fields.path("description").asString(""),
                    fields.path("status").path("name").asString(""),
                    fields.path("issuetype").path("name").asString(""),
                    labels,
                    inline);
        }
        try {
            return jira.getIssue(issueKey);
        } catch (Exception e) {
            throw new AgentExecutionException(
                    "Could not fetch " + issueKey + " from Jira and no inline issue was supplied: "
                            + e.getMessage(), e);
        }
    }

    private List<JiraIssue> fetchLinkedIssues(String issueKey) {
        try {
            return jira.getLinkedIssues(issueKey);
        } catch (Exception e) {
            log.warn("[{}] Could not fetch linked issues for {}: {}", ID, issueKey, e.getMessage());
            return List.of();
        }
    }

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern JIRA_URL_KEY = Pattern.compile("/browse/([A-Z][A-Z0-9]*-\\d+)");

    /**
     * Free-text URLs in the story description that point at another ticket on this same Jira
     * instance are treated as linked issues discovered by regex instead of by the structured
     * {@code issuelinks} field - same fetch, same rendering, just a second way to find the key.
     * Only URLs matching the factory's own configured Jira base URL are followed: a link to a
     * foreign Jira instance would fail auth anyway, and no auth-guessing is attempted here.
     * Never throws - a fetch failure for one discovered key is logged and skipped, same as the
     * existing {@link #fetchLinkedIssues} degradation.
     */
    private List<JiraIssue> mergeUrlReferencedIssues(JiraIssue issue, List<JiraIssue> linked) {
        if (jiraBaseUrl.isBlank() || issue.description() == null || issue.description().isBlank()) {
            return linked;
        }
        Set<String> already = new LinkedHashSet<>();
        if (issue.key() != null) already.add(issue.key().toUpperCase(Locale.ROOT));
        for (JiraIssue li : linked) already.add(li.key().toUpperCase(Locale.ROOT));

        List<JiraIssue> extra = new ArrayList<>();
        Matcher urlMatcher = URL_PATTERN.matcher(issue.description());
        while (urlMatcher.find()) {
            String url = stripTrailingPunctuation(urlMatcher.group());
            if (!url.startsWith(jiraBaseUrl)) continue;
            Matcher keyMatcher = JIRA_URL_KEY.matcher(url);
            if (!keyMatcher.find()) continue;
            String key = keyMatcher.group(1).toUpperCase(Locale.ROOT);
            if (!already.add(key)) continue;
            try {
                extra.add(jira.getIssue(key));
            } catch (Exception e) {
                log.warn("[{}] Could not fetch Jira issue {} referenced by URL in {}: {}",
                        ID, key, issue.key(), e.getMessage());
            }
        }
        if (extra.isEmpty()) return linked;
        List<JiraIssue> merged = new ArrayList<>(linked);
        merged.addAll(extra);
        return merged;
    }

    private static String stripTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ")].,;:!?\"'".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    private record RouterResult(List<String> areas, String why) {}

    /**
     * {@code kept} always leads with the originating ticket - it is never dropped, even when its
     * own project is excluded, because a case with no reference is orphaned in TestRail.
     * {@code excluded} is what the exclusion list removed, so the reviewer can see it at the gate.
     */
    record Refs(List<String> kept, List<String> excluded, boolean originatingExcluded) {
        String joined() {
            return String.join(", ", kept);
        }
    }

    /**
     * SKILL.md steps 1 and 3 are judgment, not keyword matching. Any failure here degrades
     * to {@link AreaDetector}'s keyword scoring — the router must never break the run.
     */
    private RouterResult route(JiraIssue issue, List<JiraIssue> linked, PromptDump.Run dump) {
        try {
            Map<String, Object> routerVars = Map.of(
                    "issue", renderIssue(issue),
                    "linked_issues", renderLinkedIssues(linked),
                    "area_table", areaDetector.areaTable());
            dump.write("router", ROUTER_PROMPT_ID, routerVars);
            String response = callForText(ROUTER_PROMPT_ID, routerVars);
            dump.writeResponse("router", response);
            if (response == null || response.isBlank()) {
                log.warn("[{}] Router returned nothing - falling back to keyword area detection", ID);
                return new RouterResult(List.of(), "router returned no content");
            }
            return new RouterResult(
                    splitList(firstGroup(ROUTER_AREAS, response)),
                    firstGroup(ROUTER_WHY, response));
        } catch (Exception e) {
            log.warn("[{}] Router call failed ({}) - falling back to keyword area detection",
                    ID, e.getMessage());
            return new RouterResult(List.of(), "router call failed: " + e.getMessage());
        }
    }

    /**
     * Picks which existing cases are read in full before the generator judges duplicates.
     *
     * <p>The index carries only titles and truncated preconditions, and a title that looks
     * like a match is exactly what produces a wrong EXISTING verdict — so the cases at risk
     * are the ones triage nominates. Selection is the model's; the bounds are not. Ids outside
     * this run's index are dropped, the count is capped, and any failure degrades to the index
     * alone rather than failing the step.</p>
     */
    private String triageCoverage(CoveragePrefetch.Coverage coverage, JiraIssue issue,
                                  PromptDump.Run dump, TokenUsage[] totalUsage) {
        if (coverage.isEmpty()) return "";
        try {
            Map<String, Object> triageVars = Map.of(
                    "issue", renderIssue(issue),
                    "coverage", coverage.index());
            dump.write("coverage-triage", TRIAGE_PROMPT_ID, triageVars);
            String response = callForText(TRIAGE_PROMPT_ID, triageVars);
            dump.writeResponse("coverage-triage", response);
            totalUsage[0] = totalUsage[0].plus(drainUsage());

            List<Long> ids = coverage.select(parseCandidateIds(response), MAX_COVERAGE_CANDIDATES);
            if (ids.isEmpty()) {
                log.info("[{}] Coverage triage nominated no cases - judging on titles and "
                        + "preconditions alone", ID);
                return "";
            }
            log.info("[{}] Coverage triage nominated {} of {} case(s) for a full read: {}",
                    ID, ids.size(), coverage.cases().size(), ids);
            return coverage.renderCandidates(ids);
        } catch (Exception e) {
            log.warn("[{}] Coverage triage failed ({}) - continuing with titles and "
                    + "preconditions only", ID, e.getMessage());
            return "";
        }
    }

    /** {@code CANDIDATES: C12345, C12408} - anything else on the line is ignored. */
    static List<Long> parseCandidateIds(String response) {
        String line = firstGroup(TRIAGE_CANDIDATES, response);
        if (line.isBlank() || line.strip().equalsIgnoreCase("none")) return List.of();
        List<Long> ids = new ArrayList<>();
        Matcher m = CASE_ID.matcher(line);
        while (m.find()) {
            try {
                ids.add(Long.parseLong(m.group(1)));
            } catch (NumberFormatException ignored) {
                // a token too long for a case id is not one
            }
        }
        return ids;
    }

    /**
     * The originating ticket is always first and can never be dropped: a case's own `refs` are
     * purely additive to it, and anything that is not a well-formed Jira key is discarded.
     * Reused two ways: once per case (both here at generation time, for the model's own
     * declared refs, and again by TestRail sync at upload time) and once as the plan-level
     * union in {@link #unionRefs}.
     */
    static Refs buildRefs(String issueKey, List<String> caseRefs, Set<String> excludedProjects) {
        Set<String> excluded = excludedProjects == null ? Set.of() : excludedProjects;
        List<String> refs = new ArrayList<>();
        refs.add(issueKey);
        List<String> dropped = new ArrayList<>();
        if (caseRefs != null) {
            for (String extra : caseRefs) {
                String k = extra.strip().toUpperCase(Locale.ROOT);
                if (!JIRA_KEY.matcher(k).matches() || refs.contains(k) || dropped.contains(k)) continue;
                if (excluded.contains(projectKey(k))) {
                    dropped.add(k);
                } else {
                    refs.add(k);
                }
            }
        }
        return new Refs(refs, dropped, excluded.contains(projectKey(issueKey)));
    }

    /**
     * The plan-level `refs` frontmatter is a mechanical rollup of every case's own `refs` -
     * each case decides for itself which linked tickets it verifies (see {@link #buildRefs}),
     * and this just deduplicates the union so TestRail sync and the HITL gate have one
     * plan-wide value to show, in addition to each case's own.
     */
    static List<String> unionRefs(String issueKey, List<TestPlanParser.ParsedCase> cases,
                                   Set<String> excludedProjects) {
        List<String> union = new ArrayList<>();
        for (TestPlanParser.ParsedCase c : cases) {
            for (String k : buildRefs(issueKey, c.refs(), excludedProjects).kept()) {
                if (!union.contains(k)) union.add(k);
            }
        }
        return union;
    }

    private static String projectKey(String jiraKey) {
        int dash = jiraKey == null ? -1 : jiraKey.lastIndexOf('-');
        return dash < 0 ? "" : jiraKey.substring(0, dash).toUpperCase(Locale.ROOT);
    }

    // ── C/D · Generate + validate ────────────────────────────────────────────

    /**
     * Generates and parses with the same parser TestRail sync uses. One format-correction
     * retry, then the step fails — sync can never receive an unparseable plan.
     */
    private String generateAndValidate(Map<String, Object> vars, TokenUsage[] totalUsage,
                                       PromptDump.Run dump, int attempt) {
        dump.write("generate-attempt" + attempt, GEN_PROMPT_ID, vars);
        String plan = callForText(GEN_PROMPT_ID, vars);
        dump.writeResponse("generate-attempt" + attempt, plan);
        totalUsage[0] = totalUsage[0].plus(drainUsage());

        List<TestPlanParser.Problem> problems = parser.validate(parser.parse(plan));
        if (problems.isEmpty()) return plan;

        log.warn("[{}] Generated plan failed structural validation ({}) - re-asking once",
                ID, describe(problems));
        Map<String, Object> retryVars = new HashMap<>(vars);
        retryVars.put("review_feedback", vars.getOrDefault("review_feedback", "")
                + "\n\n## Output format errors - fix before anything else\n\n"
                + "Your previous response could not be parsed. Reproduce the case-block format "
                + "EXACTLY as specified, under a `# Test Cases` heading:\n\n"
                + describe(problems) + "\n");
        String retryLabel = "generate-attempt" + attempt + "-retry";
        dump.write(retryLabel, GEN_PROMPT_ID, retryVars);
        plan = callForText(GEN_PROMPT_ID, retryVars);
        dump.writeResponse(retryLabel, plan);
        totalUsage[0] = totalUsage[0].plus(drainUsage());

        problems = parser.validate(parser.parse(plan));
        if (!problems.isEmpty()) {
            throw new AgentExecutionException(ID + " produced an unparseable test plan twice: "
                    + describe(problems));
        }
        return plan;
    }

    private static String describe(List<TestPlanParser.Problem> problems) {
        StringBuilder sb = new StringBuilder();
        for (TestPlanParser.Problem p : problems) {
            sb.append("- ").append(p.caseId()).append(": ").append(p.detail()).append('\n');
        }
        return sb.toString().strip();
    }

    // ── E · Review ───────────────────────────────────────────────────────────

    /**
     * Drops the automation notes before review. They are addressed to an automation engineer and
     * are required by delta 5 to name real helpers - `marcAuthority.js`, `Select('Source')`,
     * `edit()` - which is exactly the shape R5 bans in executor-facing text. Leaving them in the
     * slice produced a whole run of false positives that the generator could not fix by editing,
     * so the loop burned every iteration and still reported FAIL. The prompt says not to flag
     * them; this makes it impossible.
     */
    static String forReview(String casesSection) {
        String stripped = ATTACHMENT_CONTENT_BLOCK.matcher(casesSection).replaceAll("");
        return AUTOMATION_NOTES.matcher(stripped).replaceAll("");
    }

    /**
     * A PASS is the one verdict worth distrusting, since it's the moment a plan ships to the
     * gate unexamined by a human - a FAIL just triggers the same regeneration loop either way,
     * accurate or not. So when {@code reviewDoubleCheck} is on, a PASS gets one independent
     * second opinion (fresh call, identical input) before being accepted; either call saying
     * FAIL makes the whole thing a FAIL for this attempt, using the dissenting call's issues.
     *
     * <p>Cost-conscious by construction, not by a token/budget cap: the extra call only ever
     * fires on a PASS, so a run that fails every attempt (exhausts {@code maxReviewIterations}
     * without ever passing) pays nothing extra, and a run that passes on attempt N pays for
     * exactly one extra call total - not one per attempt.</p>
     */
    private ReviewFeedback reviewTestCases(String casesSection, String issue, int attempt,
                                           PromptDump.Run dump) {
        ReviewFeedback primary = callReviewer(casesSection, issue, attempt, dump,
                "review-attempt" + attempt);
        if (!primary.passed() || !reviewDoubleCheck) {
            return primary;
        }
        log.info("[{}] Attempt {}/{} passed - confirming with an independent second opinion",
                ID, attempt, maxReviewIterations);
        ReviewFeedback verification = callReviewer(casesSection, issue, attempt, dump,
                "review-attempt" + attempt + "-verify");
        if (!verification.passed()) {
            log.info("[{}] Second opinion on attempt {}/{} disagreed - treating as FAIL",
                    ID, attempt, maxReviewIterations);
        }
        return verification;
    }

    private ReviewFeedback callReviewer(String casesSection, String issue, int attempt,
                                        PromptDump.Run dump, String label) {
        Map<String, Object> reviewVars = Map.of(
                "test_cases", casesSection,
                "issue", issue,
                "attempt", attempt,
                "max_attempts", maxReviewIterations);
        dump.write(label, REVIEWER_PROMPT_ID, reviewVars);
        String result = callForText(REVIEWER_PROMPT_ID, reviewVars);
        dump.writeResponse(label, result);
        boolean passed = result.contains("REVIEW_RESULT: PASS");
        return new ReviewFeedback(passed, passed ? "" : extractIssuesBlock(result), result);
    }

    private String extractIssuesBlock(String reviewText) {
        int failIdx = reviewText.indexOf("REVIEW_RESULT: FAIL");
        int searchFrom = failIdx >= 0 ? failIdx : 0;
        int issuesIdx = reviewText.indexOf("Issues:", searchFrom);
        return issuesIdx >= 0 ? reviewText.substring(issuesIdx) : reviewText.substring(searchFrom);
    }

    /**
     * Structural MARC validity is not a judgment call the LLM reviewer can be trusted with -
     * leader/directory byte arithmetic is exactly the kind of precise counting an LLM gets
     * subtly wrong. Checked deterministically here instead, folded into the same retry budget
     * as the LLM review rather than a second loop, per the design: one plan, one set of issues,
     * one regeneration per failed attempt.
     */
    private List<String> validateAttachments(List<TestPlanParser.ParsedCase> cases) {
        List<String> errors = new ArrayList<>();
        for (TestPlanParser.ParsedCase c : cases) {
            TestPlanParser.AttachmentKind kind = c.attachmentKind();
            if (kind == null || !kind.isMarc()) continue;
            List<MarcFixtureBuilder.Result> results = MarcFixtureBuilder.buildAll(c.attachmentContent(), kind);
            boolean multi = results.size() > 1;
            for (int i = 0; i < results.size(); i++) {
                MarcFixtureBuilder.Result result = results.get(i);
                String label = multi ? kind.spec() + " record " + (i + 1) : kind.spec();
                if (!result.valid()) {
                    for (String err : result.errors()) {
                        errors.add(c.id() + " attachment (" + label + "): " + err);
                    }
                }
            }
        }
        return errors;
    }

    private static ReviewFeedback mergeAttachmentErrors(ReviewFeedback feedback, List<String> attachmentErrors) {
        StringBuilder issues = new StringBuilder("Issues:\n");
        for (String err : attachmentErrors) {
            issues.append("- ").append(err).append('\n');
        }
        if (!feedback.passed() && !feedback.issuesBlock().isBlank()) {
            issues.append(feedback.issuesBlock()).append('\n');
        }
        return new ReviewFeedback(false, issues.toString().strip(),
                feedback.fullText() + "\n\n[Attachment content also failed deterministic MARC validation - "
                        + "see merged Issues above.]");
    }

    private String buildFeedbackBlock(String issuesBlock, int attempt) {
        return "\n## Quality gate feedback - attempt " + attempt + " of " + maxReviewIterations + "\n\n"
                + "The previous version failed the automated quality review. "
                + "**Edit the previous version shown below - fix only the flagged steps; "
                + "do not change steps or cases that had no issues.**\n\n"
                + "**Reproduce `# Analysis` in full — it is not a changelog.** "
                + "The `## Scenarios` numbered list and `## Coverage matrix` table are mandatory "
                + "on every attempt, including this one. Do not replace `# Analysis` with a "
                + "summary of what you fixed. If a fix changes something in it, edit that part "
                + "and leave the rest untouched.\n\n"
                + "Banned patterns found (must not appear anywhere - not just in the listed instances):\n\n"
                + issuesBlock + "\n\n"
                + "Rule reminders:\n"
                + "- R1: Banned phrases: \"works correctly\", \"confirming\", \"is successful\", "
                + "bare \"is displayed\" without element name. "
                + "Navigation steps that name the destination (\"Package detail record opens\") are NOT R1 violations.\n"
                + "- R2: Toast/message/notification - exact quoted text or \"(verify exact wording on first execution)\"\n"
                + "- R3: Numeric assertions - absolute final value, never \"decreased accordingly\"\n"
                + "- R4: Two or more Field = value pairs - `\\n- Field = value` bullet list, never joined inline\n"
                + "- R11: Three or more named items listed inline - lead-in line + bullet list\n"
                + "- R6: Step actions - imperative style, never \"User clicks\"\n"
                + "- R7: Every step must have a non-empty expected result\n"
                + "- R10: At least one step per case must assert a concrete domain value, not only navigation\n"
                + "- R12: Double-quote every on-screen string anywhere in the case - labels AND option/status/message values; backticks do not count\n"
                + "- R13: Capability Sets and permissions - label correctly, one kind per list\n"
                + "- R14: One expected result asserting several separate checks - lead-in + bullet list. "
                + "Recursive: a bullet that itself joins 2+ facts with \"and\"/semicolon is still a "
                + "violation even though the step already has bullets - split that bullet too. The lead-in "
                + "itself is subject to this rule too: it may only name the context/screen, not assert a "
                + "second fact (e.g. \"page opens with an empty X field\" bundles two facts before the "
                + "bullets even start) - pull any such fact out into its own bullet\n";
    }

    private String buildPreviousAttemptSection(String plan, int attempt) {
        return "\n## Previous version (attempt " + attempt + ") - edit this, do not regenerate from scratch\n\n"
                + "Fix only the steps listed in the quality gate feedback above. "
                + "All other cases and steps must be reproduced unchanged.\n\n"
                + plan;
    }

    // ── F · Assemble ─────────────────────────────────────────────────────────

    /**
     * The implementing team owns the tests, so the story's Dev Team field is authoritative and
     * the agent cannot override it. The agent's reading is a fallback for tickets where the
     * field is empty — it has no other source for the team, so it would otherwise infer one
     * from the subject matter and be confidently wrong.
     */
    private String resolveTeam(String analysis, JiraIssue issue) {
        String fromJira = devTeam(issue);
        if (!fromJira.isBlank()) {
            // Canonicalise so sync gets the spelling dev-teams.md indexes, whatever
            // capitalisation or "… Team" decoration Jira happens to return.
            return devTeams.canonicalName(fromJira);
        }
        String fromAgent = stripMarkup(firstGroup(ANALYSIS_TEAM, analysis));
        if (!fromAgent.isBlank() && !fromAgent.equalsIgnoreCase("unknown")) {
            return devTeams.canonicalName(fromAgent);
        }
        return "";
    }

    /**
     * Reads the Dev Team from the cleaned Jira payload. {@code JiraRestConnector} renames
     * {@code customfield_10057} to {@code devTeam} and strips the raw {@code customfield_*}
     * keys, so this must look up the renamed key. The field is a single select whose JSON shape
     * varies by Jira configuration, hence the ordered probe rather than one fixed path.
     */
    static String devTeam(JiraIssue issue) {
        if (issue == null || issue.raw() == null) {
            return "";
        }
        JsonNode node = issue.raw().path("fields").path("devTeam");
        if (node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asString("").strip();
        }
        for (String key : List.of("title", "value", "name")) {
            String candidate = node.path(key).asString("").strip();
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private String resolveEcsStory(String analysis) {
        String val = firstGroup(ANALYSIS_ECS_STORY, analysis).strip().toLowerCase(Locale.ROOT);
        return val.isEmpty() ? "non-ecs" : val;
    }

    /**
     * The agent's section only survives if TestRail can actually resolve it; otherwise the
     * configured default is used so sync never fails on an invented path.
     */
    private String resolveSection(String analysis) {
        String suggested = stripMarkup(firstGroup(ANALYSIS_SECTION, analysis));
        if (suggested.isBlank()) return defaultSection();
        try {
            testRail.resolveSectionByPath(suggested);
            return suggested;
        } catch (Exception e) {
            log.warn("[{}] Suggested TestRail section '{}' does not resolve ({}) - using default",
                    ID, suggested, e.getMessage());
            return defaultSection();
        }
    }

    private String defaultSection() {
        Long id = properties.testrailSectionId();
        return id != null ? String.valueOf(id) : "";
    }

    private String buildSectionsBlock() {
        try {
            List<String> paths = testRail.listSectionPaths();
            if (paths.isEmpty()) {
                return "(TestRail not configured - suggest the section path based on the issue area)";
            }
            return String.join("\n", paths);
        } catch (Exception e) {
            return "(TestRail sections unavailable - suggest the section path based on the issue area)";
        }
    }

    private String renderReviewSection(ReviewFeedback feedback, int iterations) {
        StringBuilder sb = new StringBuilder("# Review\n\n");
        if (feedback.passed()) {
            sb.append("**Result:** PASSED after ").append(iterations).append(" iteration(s)\n\n");
            sb.append(feedback.fullText());
        } else if (feedback.issuesBlock().isBlank()) {
            // No issues to list means the reviewer never produced a verdict, so the cases are
            // unchecked rather than defective - saying "remaining issues" with an empty list
            // would tell the reviewer at the gate exactly the wrong thing.
            sb.append("**Result:** NOT CHECKED after ").append(iterations)
              .append(" iteration(s)\n\n");
            sb.append(feedback.fullText());
        } else {
            sb.append("**Result:** FAILED after ").append(iterations).append(" iteration(s)\n\n");
            sb.append("Remaining issues (amend the cases above at gate-1):\n\n");
            sb.append(feedback.issuesBlock());
        }
        return sb.append('\n').toString();
    }

    /** Replaces the agent's (intentionally empty) {@code # Review} section with the verdict. */
    private static String replaceReviewSection(String plan, String reviewSection) {
        int idx = plan.indexOf("\n# Review");
        String withoutReview = idx >= 0 ? plan.substring(0, idx) : plan;
        return withoutReview.stripTrailing() + "\n\n" + reviewSection;
    }

    // ── Prompt blocks ────────────────────────────────────────────────────────

    private String buildProvenanceBlock(RouterResult route, List<String> areas,
                                        ContextPrefetch.ContextBundle ctx,
                                        ExamplesPrefetch.Examples examples,
                                        FragmentPrefetch.Fragments fragments, String skill) {
        StringBuilder sb = new StringBuilder("## Provenance (state this verbatim at the top of `# Analysis`)\n\n");
        sb.append("- Area(s): ").append(String.join(", ", areas));
        if (!route.why().isBlank()) sb.append(" — ").append(route.why());
        sb.append('\n');
        Set<String> excludedProjects = properties.refsExcludedProjects();
        if (!excludedProjects.isEmpty()) {
            sb.append("- Refs: never cite a ticket from project(s) ")
              .append(String.join(", ", excludedProjects))
              .append(" in any case's `- **Refs:**` line — those projects track test/automation "
                      + "work, not product behaviour.\n");
        }
        sb.append("- Context: ").append(ctx.sourceNote()).append('\n');
        sb.append("- Examples: ").append(examples.sourceNote()).append('\n');
        if (!ctx.missingAreas().isEmpty()) {
            sb.append("- ⚠️ No context file for: ").append(String.join(", ", ctx.missingAreas()))
              .append(" — generate from the User Story alone for that area and say so.\n");
        }
        if (!ctx.qualityNote().isBlank()) {
            sb.append("- Context quality: ").append(ctx.qualityNote());
            if (ctx.enriched()) sb.append(" (see the Live Enrichment section)");
            sb.append(".\n");
        }
        sb.append("- TAF fragments: ").append(fragments.sourceNote()).append('\n');
        if (fragments.unavailable()) {
            sb.append("- ⚠️ Fragment library unavailable — `Automatable` flags and automation notes "
                    + "are best-effort; ask the reviewer to verify them.\n");
        }
        List<String> unanchored = skills.unanchoredDeltas(skill);
        if (!unanchored.isEmpty()) {
            sb.append("- ⚠️ SKILL.md has changed: ").append(String.join(", ", unanchored))
              .append(" no longer match the skill text. The factory deltas may be stale — "
                      + "state this at the top of `# Analysis` so a human can fix the prompt.\n");
        }
        return sb.toString();
    }

    private static String buildHitlFeedbackBlock(ArtifactContent hitlFeedback) {
        if (hitlFeedback == null || hitlFeedback.content().isBlank()) return "";
        return "\n## QA rejection feedback (PRIORITY - address before anything else)\n\n"
                + "A QA reviewer rejected the previous test cases. Every point below must be "
                + "addressed in the new generation:\n\n"
                + hitlFeedback.content() + "\n";
    }

    private static String buildPreviousPlanBlock(ArtifactContent previousPlan) {
        if (previousPlan == null || previousPlan.content().isBlank()) return "";
        return "\n## Previous test plan (rejected - use as structural reference)\n\n"
                + "Keep the structure and scope where the feedback does not require changes. "
                + "Fix only what the feedback calls out.\n\n"
                + previousPlan.content() + "\n";
    }

    private String renderIssue(JiraIssue issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Key:** ").append(issue.key()).append('\n');
        sb.append("**Summary:** ").append(nullSafe(issue.summary())).append('\n');
        sb.append("**Type:** ").append(nullSafe(issue.issueType())).append('\n');
        sb.append("**Status:** ").append(nullSafe(issue.status())).append('\n');
        // Without this the agent has no source for the team at all and infers one from the
        // subject matter, which is plausible-sounding and wrong.
        String team = devTeam(issue);
        sb.append("**Development Team:** ").append(team.isBlank() ? "not set on the ticket" : team)
                .append('\n');
        if (issue.labels() != null && !issue.labels().isEmpty()) {
            sb.append("**Labels:** ").append(String.join(", ", issue.labels())).append('\n');
            String mismatch = labelTeamMismatch(team, issue.labels());
            if (!mismatch.isEmpty()) {
                sb.append("**⚠️ Team/label mismatch:** ").append(mismatch).append('\n');
            }
        }
        sb.append("\n**Description (verbatim):**\n\n").append(nullSafe(issue.description())).append('\n');
        return sb.toString();
    }

    /**
     * Surfaces disagreement between the Development Team field and any {@code epam-<team>}
     * label, so a story like a reorg-stale ticket (field still says the old team, label says
     * the new one) is flagged instead of silently trusting whichever the field happens to say.
     * Not a substitute for the {@code dev-teams.md} remap table - this catches mismatches that
     * table doesn't yet know about; once a team is remapped there, both sides resolve to the
     * same canonical name and no warning fires.
     */
    private String labelTeamMismatch(String team, List<String> labels) {
        if (team == null || team.isBlank()) {
            return "";
        }
        String canonicalTeam = devTeams.canonicalName(team);
        Set<String> labelTeams = new LinkedHashSet<>();
        for (String label : labels) {
            Matcher m = EPAM_TEAM_LABEL.matcher(label == null ? "" : label.strip());
            if (m.matches()) {
                String candidate = devTeams.canonicalName(m.group(1).replace('_', ' ').replace('-', ' '));
                if (!candidate.isBlank()) {
                    labelTeams.add(candidate);
                }
            }
        }
        labelTeams.remove(canonicalTeam);
        if (labelTeams.isEmpty()) {
            return "";
        }
        return "Development Team field says \"" + canonicalTeam + "\", but label(s) suggest "
                + String.join(", ", labelTeams) + " - verify which is current before syncing.";
    }

    private static String renderLinkedIssues(List<JiraIssue> linked) {
        if (linked == null || linked.isEmpty()) return "_No linked issues._";
        StringBuilder sb = new StringBuilder();
        for (JiraIssue li : linked) {
            sb.append("**").append(li.key()).append("** (").append(nullSafe(li.issueType()))
              .append("): ").append(nullSafe(li.summary())).append('\n');
            if (li.description() != null && !li.description().isBlank()) {
                String desc = li.description();
                if (desc.length() > 1000) desc = desc.substring(0, 1000) + "…";
                sb.append("> ").append(desc.replace("\n", "\n> ")).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String sectionOf(String plan, String heading) {
        int start = plan.indexOf(heading);
        if (start < 0) return "";
        int end = plan.indexOf("\n# ", start + heading.length());
        return end > 0 ? plan.substring(start, end) : plan.substring(start);
    }

    private static String firstGroup(Pattern pattern, String text) {
        if (text == null) return "";
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).strip() : "";
    }

    private static List<String> splitList(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.split("[,;]")) {
            String s = part.strip();
            if (!s.isEmpty() && !s.equalsIgnoreCase("none")) out.add(s);
        }
        return out;
    }

    private static String stripMarkup(String value) {
        return value.replaceAll("[`*\"']", "").strip();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private record ReviewFeedback(boolean passed, String issuesBlock, String fullText) {}
}
