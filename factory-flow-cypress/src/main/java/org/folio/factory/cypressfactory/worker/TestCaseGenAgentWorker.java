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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single LLM worker of the Cypress flow: it runs MQA's {@code write-testrail-cases}
 * skill end to end in one session.
 *
 * <p>Phases (see CYPRESS_FLOW_PLAN.md Part I §2):</p>
 * <ol>
 *   <li><b>Fetch &amp; route</b> — raw Jira issue, then one small LLM call for the two
 *       genuinely judgment-based decisions SKILL.md steps 1 and 3 describe: which area
 *       context file(s) to load, and what belongs in {@code refs}.</li>
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

    private static final Logger log = LoggerFactory.getLogger(TestCaseGenAgentWorker.class);

    private static final Pattern JIRA_KEY = Pattern.compile("[A-Z][A-Z0-9]*-\\d+");
    private static final Pattern ROUTER_AREAS = Pattern.compile("(?m)^\\s*AREAS:\\s*(.+)$");
    private static final Pattern ROUTER_REFS = Pattern.compile("(?m)^\\s*REFS:\\s*(.+)$");
    private static final Pattern ROUTER_WHY = Pattern.compile("(?m)^\\s*WHY:\\s*(.+)$");
    private static final Pattern ANALYSIS_TEAM = Pattern.compile("(?mi)^\\s*\\**team\\**:\\s*(.+?)\\s*$");
    private static final Pattern ANALYSIS_ECS = Pattern.compile("(?mi)^\\s*\\**ecs_enabled\\**:\\s*(\\w+)");
    /** The three delta-4 fields, once promoted to frontmatter, are removed from the prose. */
    private static final Pattern PROMOTED_FIELD = Pattern.compile(
            "(?mi)^[ \\t]*\\**(?:testrail_section|team|ecs_enabled)\\**[ \\t]*:.*(?:\\R|$)");
    /** A `---` fence pair left empty once its fields were stripped. */
    private static final Pattern EMPTY_FENCE =
            Pattern.compile("(?m)^---[ \\t]*\\R(?:[ \\t]*\\R)*^---[ \\t]*\\R");
    private static final Pattern BLANK_RUN = Pattern.compile("(?:[ \\t]*\\R){3,}");

    private static final Pattern ANALYSIS_SECTION =
            Pattern.compile("(?mi)^\\s*\\**testrail_section\\**:\\s*[\"']?(.+?)[\"']?\\s*$");

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
    private final int maxReviewIterations;

    public TestCaseGenAgentWorker(ChatClient chatClient, JiraConnector jira, TestRailConnector testRail,
                                  SkillWorkspaceService skills, AreaDetector areaDetector,
                                  ContextPrefetch contextPrefetch, FragmentPrefetch fragmentPrefetch,
                                  CoveragePrefetch coveragePrefetch, ExamplesPrefetch examplesPrefetch,
                                  FrontmatterCodec frontmatterCodec, TestPlanParser parser,
                                  DevTeamCatalog devTeams, PromptDump promptDump,
                                  CypressFlowProperties properties) {
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
        this.maxReviewIterations = properties.maxReviewIterations();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected Integer maxTokens() {
        return 16384;
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
        return AnthropicChatOptions.builder()
                .maxTokens(maxTokens())
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
        List<JiraIssue> linked = fetchLinkedIssues(issueKey);

        // SKILL.md is the whole methodology - refuse to generate without it.
        String skill = skills.requireSkill();

        RouterResult route = route(issue, linked, dump);
        totalUsage[0] = totalUsage[0].plus(drainUsage());
        List<String> areas = areaDetector.resolve(route.areas(), issue);
        List<String> refs = buildRefs(issueKey, route.refs());
        log.info("[{}] Area(s) {} · refs {} · {}", ID, areas, refs, route.why());

        // ── B · Prefetch ─────────────────────────────────────────────────────
        ContextPrefetch.ContextBundle ctx = contextPrefetch.load(areas, issue);
        ExamplesPrefetch.Examples examples = examplesPrefetch.load();
        String fragments = fragmentPrefetch.scan(areas, issue.summary());
        String coverage = coveragePrefetch.load(areas);
        String sections = buildSectionsBlock();

        ArtifactContent previousPlan = context.inputs().get("test_plan.md");
        ArtifactContent hitlFeedback = context.inputs().get("hitl_feedback.md");

        Map<String, Object> vars = new HashMap<>();
        vars.put("skill", skill);
        vars.put("examples", examples.text());
        vars.put("context_files", ctx.text());
        vars.put("enrichment", buildProvenanceBlock(route, areas, ctx, examples, fragments, skill));
        vars.put("issue", renderIssue(issue));
        vars.put("dev_teams", devTeams.nameList());
        vars.put("linked_issues", renderLinkedIssues(linked));
        vars.put("fragments", fragments);
        vars.put("coverage", coverage);
        vars.put("sections", sections);
        vars.put("refs", String.join(", ", refs));
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

                feedback = reviewTestCases(parser.casesSection(plan), renderIssue(issue), attempt, dump);
                totalUsage[0] = totalUsage[0].plus(drainUsage());
                if (feedback.passed()) {
                    log.info("[{}] Quality review PASSED on attempt {}/{}", ID, attempt, maxReviewIterations);
                    break;
                }
                long issueCount = feedback.issuesBlock().lines()
                        .filter(l -> l.strip().startsWith("-")).count();
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
        metadata.put("refs", String.join(", ", refs));
        metadata.put("team", resolveTeam(analysis, issue));
        metadata.put("ecs_enabled", resolveEcs(analysis));
        metadata.put("testrail_section", resolveSection(analysis));
        metadata.put("case_count", cases.size());
        metadata.put("review_passed", feedback.passed());
        metadata.put("review_iterations", attempt);

        String body = stripPromotedFields(
                replaceReviewSection(plan, renderReviewSection(feedback, attempt)));

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
        return BLANK_RUN.matcher(stripped).replaceAll("\n\n") + tail;
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

    private record RouterResult(List<String> areas, List<String> refs, String why) {}

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
            if (response == null || response.isBlank()) {
                log.warn("[{}] Router returned nothing - falling back to keyword area detection", ID);
                return new RouterResult(List.of(), List.of(), "router returned no content");
            }
            return new RouterResult(
                    splitList(firstGroup(ROUTER_AREAS, response)),
                    splitList(firstGroup(ROUTER_REFS, response)),
                    firstGroup(ROUTER_WHY, response));
        } catch (Exception e) {
            log.warn("[{}] Router call failed ({}) - falling back to keyword area detection",
                    ID, e.getMessage());
            return new RouterResult(List.of(), List.of(), "router call failed: " + e.getMessage());
        }
    }

    /**
     * The originating ticket is always first and can never be dropped: the router is purely
     * additive, and anything that is not a well-formed Jira key is discarded.
     */
    static List<String> buildRefs(String issueKey, List<String> routerRefs) {
        List<String> refs = new ArrayList<>();
        refs.add(issueKey);
        if (routerRefs != null) {
            for (String extra : routerRefs) {
                String k = extra.strip().toUpperCase(Locale.ROOT);
                if (JIRA_KEY.matcher(k).matches() && !refs.contains(k)) {
                    refs.add(k);
                }
            }
        }
        return refs;
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
        plan = callForText(GEN_PROMPT_ID, retryVars);
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

    private ReviewFeedback reviewTestCases(String casesSection, String issue, int attempt,
                                           PromptDump.Run dump) {
        Map<String, Object> reviewVars = Map.of(
                "test_cases", casesSection,
                "issue", issue,
                "attempt", attempt,
                "max_attempts", maxReviewIterations);
        dump.write("review-attempt" + attempt, REVIEWER_PROMPT_ID, reviewVars);
        String result = callForText(REVIEWER_PROMPT_ID, reviewVars);
        boolean passed = result.contains("REVIEW_RESULT: PASS");
        return new ReviewFeedback(passed, passed ? "" : extractIssuesBlock(result), result);
    }

    private String extractIssuesBlock(String reviewText) {
        int failIdx = reviewText.indexOf("REVIEW_RESULT: FAIL");
        int searchFrom = failIdx >= 0 ? failIdx : 0;
        int issuesIdx = reviewText.indexOf("Issues:", searchFrom);
        return issuesIdx >= 0 ? reviewText.substring(issuesIdx) : reviewText.substring(searchFrom);
    }

    private String buildFeedbackBlock(String issuesBlock, int attempt) {
        return "\n## Quality gate feedback - attempt " + attempt + " of " + maxReviewIterations + "\n\n"
                + "The previous version failed the automated quality review. "
                + "**Edit the previous version shown below - fix only the flagged steps; "
                + "do not change steps or cases that had no issues.**\n\n"
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
                + "- R10: At least one step per case must assert a concrete domain value, not only navigation\n";
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

    private boolean resolveEcs(String analysis) {
        return "true".equalsIgnoreCase(firstGroup(ANALYSIS_ECS, analysis).strip());
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
                                        ExamplesPrefetch.Examples examples, String fragments,
                                        String skill) {
        StringBuilder sb = new StringBuilder("## Provenance (state this verbatim at the top of `# Analysis`)\n\n");
        sb.append("- Area(s): ").append(String.join(", ", areas));
        if (!route.why().isBlank()) sb.append(" — ").append(route.why());
        sb.append('\n');
        sb.append("- Context: ").append(ctx.sourceNote()).append('\n');
        sb.append("- Examples: ").append(examples.sourceNote()).append('\n');
        if (!ctx.missingAreas().isEmpty()) {
            sb.append("- ⚠️ No context file for: ").append(String.join(", ", ctx.missingAreas()))
              .append(" — generate from the User Story alone for that area and say so.\n");
        }
        if (ctx.enriched()) {
            sb.append("- Context was thin; live enrichment ran (see the Live Enrichment section).\n");
        }
        if (fragments.contains("_Unavailable —")) {
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
        }
        sb.append("\n**Description (verbatim):**\n\n").append(nullSafe(issue.description())).append('\n');
        return sb.toString();
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
