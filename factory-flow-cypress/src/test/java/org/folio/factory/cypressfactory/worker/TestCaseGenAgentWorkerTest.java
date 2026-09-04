package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.agents.llm.StubChatModel;
import org.folio.factory.connectors.UnconfiguredConnectors;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.connectors.testrail.TestRailCase;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.connectors.testrail.TestRailSectionRef;
import org.folio.factory.connectors.testrail.TestRailStep;
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
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicCacheTtl;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCaseGenAgentWorkerTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static final String ROUTER_OK = """
            AREAS: marc-authority
            REFS: MODELINKS-201
            WHY: story describes authority browse; UIMARCAUTH prefix agrees
            """;

    private static final String PLAN = """
            # Analysis

            **Area detected:** marc-authority

            testrail_section: MARC Authority › Browse
            team: Spitfire
            ecs_story: non-ecs

            ## Coverage matrix
            | AC | Scenario | Status |
            |---|---|---|
            | AC-1 | TC001 | NEW |

            # Test Cases

            ## TC001: Browse authority records by personal name
            - **Test Group:** critical
            - **User Journey:** No
            - **Automatable:** Yes
            - **ECS:** No

            **Preconditions**
            - User is logged in as admin

            **Steps**
            1. **Action:** Navigate to MARC authority
               **Expected:** Browse pane is displayed with the "Search" tab active
            2. **Action:** Click Search
               **Expected:** Results list shows Status = "Active"

            # Review
            """;

    /** No `# Test Cases` heading and no case blocks — must fail structural validation. */
    private static final String PLAN_MALFORMED = """
            # Analysis

            I could not produce cases in the requested format.

            # Review
            """;

    private static final String REVIEW_PASS = """
            REVIEW_RESULT: PASS
            All 1 cases pass all checklist rules.
            """;

    private static final String REVIEW_FAIL = """
            REVIEW_RESULT: FAIL
            Issues:
            - TC001 [R1] Step 1 expected: "Browse pane is displayed" — vague
            """;

    private static final String REVIEW_FAIL_2 = """
            REVIEW_RESULT: FAIL
            Issues:
            - TC001 [R7] Step 2 expected: "" — empty expected result
            """;

    private static final String MODEL = "claude-sonnet-4-6";

    private final FrontmatterCodec codec = new FrontmatterCodec();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    // ── Fakes ────────────────────────────────────────────────────────────────

    private static final JiraIssue ISSUE = new JiraIssue(
            "UIMARCAUTH-553", "Browse authority records by personal name",
            "AC1: browse returns matches.\nAC2: empty query is rejected.",
            "Open", "Story", List.of(), null);

    private static class FakeJira implements JiraConnector {
        private final JiraIssue issue;

        FakeJira() { this(ISSUE); }

        FakeJira(JiraIssue issue) { this.issue = issue; }

        @Override public JiraIssue getIssue(String issueKey) { return issue; }
        @Override public List<JiraIssue> getLinkedIssues(String issueKey) { return List.of(); }
        @Override public void addComment(String issueKey, String body) { }
        @Override public void transitionIssue(String issueKey, String transitionName) { }
    }

    /** SKILL.md always resolves (from the bundled classpath copy); nothing else does. */
    private SkillWorkspaceService skills() {
        return new SkillWorkspaceService(new UnconfiguredConnectors.GitHub(), properties(3));
    }

    private CypressFlowProperties properties(int maxReviewIterations) {
        return new CypressFlowProperties("", "main", "", null, null, null, null, maxReviewIterations, null);
    }

    private TestCaseGenAgentWorker worker(StubChatModel model) {
        return worker(model, 3, new UnconfiguredConnectors.TestRail());
    }

    private TestCaseGenAgentWorker worker(StubChatModel model, String llmModel) {
        return worker(model, 3, new UnconfiguredConnectors.TestRail(),
                new PromptDump(false, Path.of("target", "unused-prompt-dump")), llmModel);
    }

    private TestCaseGenAgentWorker worker(StubChatModel model, int maxReviewIterations,
                                          TestRailConnector testRail) {
        return worker(model, maxReviewIterations, testRail,
                new PromptDump(false, Path.of("target", "unused-prompt-dump")));
    }

    private TestCaseGenAgentWorker worker(StubChatModel model, JiraConnector jira) {
        CypressFlowProperties props = properties(3);
        SkillWorkspaceService skills = skills();
        return new TestCaseGenAgentWorker(
                ChatClient.create(model), jira, new UnconfiguredConnectors.TestRail(), skills,
                new AreaDetector(),
                new ContextPrefetch(skills, new UnconfiguredConnectors.GitHub(), new FakeJira(),
                        jsonMapper, props),
                new FragmentPrefetch(new TafSyncService(), new UnconfiguredConnectors.GitHub()),
                new CoveragePrefetch(new UnconfiguredConnectors.TestRail(), props),
                new ExamplesPrefetch(skills),
                codec, new TestPlanParser(), new DevTeamCatalog(),
                new PromptDump(false, Path.of("target", "unused-prompt-dump")), props, MODEL);
    }

    private TestCaseGenAgentWorker worker(StubChatModel model, int maxReviewIterations,
                                          TestRailConnector testRail, PromptDump promptDump) {
        return worker(model, maxReviewIterations, testRail, promptDump, MODEL);
    }

    private TestCaseGenAgentWorker worker(StubChatModel model, int maxReviewIterations,
                                          TestRailConnector testRail, PromptDump promptDump,
                                          String llmModel) {
        CypressFlowProperties props = properties(maxReviewIterations);
        SkillWorkspaceService skills = skills();
        return new TestCaseGenAgentWorker(
                ChatClient.create(model), new FakeJira(), testRail, skills,
                new AreaDetector(),
                new ContextPrefetch(skills, new UnconfiguredConnectors.GitHub(), new FakeJira(),
                        jsonMapper, props),
                new FragmentPrefetch(new TafSyncService(), new UnconfiguredConnectors.GitHub()),
                new CoveragePrefetch(testRail, props),
                new ExamplesPrefetch(skills),
                codec, new TestPlanParser(), new DevTeamCatalog(), promptDump, props, llmModel);
    }

    // ── Contexts ─────────────────────────────────────────────────────────────

    private AgentContext freshContext() {
        return new AgentContext(UUID.randomUUID(), "testcase-gen", Map.of(),
                trigger("UIMARCAUTH-553"), Map.of(), List.of("test_plan.md"));
    }

    private AgentContext retryContext(String previousPlan, String feedback) {
        return new AgentContext(UUID.randomUUID(), "testcase-gen",
                Map.of(
                        "test_plan.md", new ArtifactContent("test_plan.md", 1, "text/markdown", previousPlan),
                        "hitl_feedback.md", new ArtifactContent("hitl_feedback.md", 1, "text/markdown", feedback)),
                trigger("UIMARCAUTH-553"), Map.of(), List.of("test_plan.md"));
    }

    private tools.jackson.databind.JsonNode trigger(String issueKey) {
        return jsonMapper.createObjectNode().put("issueKey", issueKey);
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    void producesASingleTestPlanArtifact() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        AgentResult result = worker(model).execute(freshContext());

        assertThat(result.outputs()).containsOnlyKeys("test_plan.md");
    }

    @Test
    void usesThreeLlmCallsOnAPassFirstRun() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        assertThat(model.receivedPrompts()).hasSize(3);
    }

    @Test
    void frontmatterCarriesEveryFieldTestRailSyncNeeds() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        String plan = worker(model).execute(freshContext()).outputs().get("test_plan.md");

        assertThat(plan).contains("issue_key: \"UIMARCAUTH-553\"");
        assertThat(plan).contains("refs: \"UIMARCAUTH-553, MODELINKS-201\"");
        assertThat(plan).contains("team: \"Spitfire\"");
        assertThat(plan).contains("ecs_story: \"non-ecs\"");
        assertThat(plan).contains("case_count: 1");
        assertThat(plan).contains("review_passed: true");
        assertThat(plan).contains("review_iterations: 1");
    }

    @Test
    void issueKeyComesFromTheTriggerNotTheModel() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN.replace("UIMARCAUTH-553", "WRONG-1"))
                .enqueue(REVIEW_PASS);

        String plan = worker(model).execute(freshContext()).outputs().get("test_plan.md");

        assertThat(plan).contains("issue_key: \"UIMARCAUTH-553\"");
    }

    @Test
    void rawIssueTextReachesTheGenerationPrompt() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        String genPrompt = model.receivedPrompts().get(1).getContents();
        assertThat(genPrompt).contains("AC1: browse returns matches.");
        assertThat(genPrompt).contains("AC2: empty query is rejected.");
    }

    @Test
    void theWholeSkillTextIsCarriedIntoTheGenerationPrompt() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        String genPrompt = model.receivedPrompts().get(1).getContents();
        assertThat(genPrompt).contains("Mandatory Workflow");
        assertThat(genPrompt).contains("FACTORY DELTAS");
    }

    @Test
    void reviewSectionIsRewrittenWithTheVerdict() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        String plan = worker(model).execute(freshContext()).outputs().get("test_plan.md");

        assertThat(plan).contains("**Result:** PASSED after 1 iteration(s)");
        assertThat(plan).contains("# Test Cases");
        assertThat(plan).contains("## TC001:");
    }

    @Test
    void failsWithoutAnIssueKeyInTheTrigger() {
        StubChatModel model = new StubChatModel();
        AgentContext ctx = new AgentContext(UUID.randomUUID(), "testcase-gen", Map.of(),
                jsonMapper.createObjectNode(), Map.of(), List.of("test_plan.md"));

        assertThatThrownBy(() -> worker(model).execute(ctx))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("issueKey");
    }

    // ── Router ───────────────────────────────────────────────────────────────

    @Test
    void routerAreaDrivesTheContextLookup() {
        StubChatModel model = new StubChatModel()
                .enqueue("AREAS: fees-fines\nREFS: none\nWHY: story is about waiving\n")
                .enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        assertThat(model.receivedPrompts().get(1).getContents()).contains("fees-fines");
    }

    @Test
    void routerSeesTheAreaTable() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        String routerPrompt = model.receivedPrompts().getFirst().getContents();
        assertThat(routerPrompt).contains("marc-authority");
        assertThat(routerPrompt).contains("UIMARCAUTH");
    }

    @Test
    void unknownRouterAreaFallsBackToKeywordDetectionAndStillCompletes() {
        StubChatModel model = new StubChatModel()
                .enqueue("AREAS: not-a-real-area\nREFS: none\nWHY: guessing\n")
                .enqueue(PLAN).enqueue(REVIEW_PASS);

        AgentResult result = worker(model).execute(freshContext());

        assertThat(result.outputs()).containsKey("test_plan.md");
        // UIMARCAUTH prefix resolves to marc-authority via the fallback
        assertThat(model.receivedPrompts().get(1).getContents()).contains("marc-authority");
    }

    @Test
    void garbageRouterOutputDoesNotBreakTheRun() {
        StubChatModel model = new StubChatModel()
                .enqueue("I'm not sure which area this belongs to.")
                .enqueue(PLAN).enqueue(REVIEW_PASS);

        AgentResult result = worker(model).execute(freshContext());

        assertThat(result.outputs()).containsKey("test_plan.md");
    }

    // ── refs: the ticket key is non-negotiable ───────────────────────────────

    private static final Set<String> NO_EXCLUSIONS = Set.of();
    private static final Set<String> EXCLUDE_FAT = Set.of("FAT");

    @Test
    void refsAlwaysLeadsWithTheOriginatingTicket() {
        assertThat(TestCaseGenAgentWorker.buildRefs("ERM-1", List.of("MODFIN-391"), NO_EXCLUSIONS).kept())
                .containsExactly("ERM-1", "MODFIN-391");
    }

    @Test
    void refsSurvivesARouterThatOmitsTheTicket() {
        assertThat(TestCaseGenAgentWorker.buildRefs("ERM-1", List.of(), NO_EXCLUSIONS).kept())
                .containsExactly("ERM-1");
    }

    @Test
    void refsDropsMalformedRouterValues() {
        assertThat(TestCaseGenAgentWorker.buildRefs("ERM-1",
                List.of("the story", "ERM", "123", "MODFIN-391", "not a key"), NO_EXCLUSIONS).kept())
                .containsExactly("ERM-1", "MODFIN-391");
    }

    @Test
    void refsDeduplicatesCaseInsensitively() {
        assertThat(TestCaseGenAgentWorker.buildRefs("ERM-1",
                List.of("erm-1", "MODFIN-391", "modfin-391"), NO_EXCLUSIONS).kept())
                .containsExactly("ERM-1", "MODFIN-391");
    }

    @Test
    void refsSkipsExcludedProjectsAndReportsThem() {
        TestCaseGenAgentWorker.Refs refs = TestCaseGenAgentWorker.buildRefs("ERM-1",
                List.of("FAT-4021", "MODFIN-391", "fat-4022"), EXCLUDE_FAT);
        assertThat(refs.kept()).containsExactly("ERM-1", "MODFIN-391");
        assertThat(refs.excluded()).containsExactly("FAT-4021", "FAT-4022");
        assertThat(refs.originatingExcluded()).isFalse();
    }

    @Test
    void refsKeepsAnOriginatingTicketFromAnExcludedProjectButFlagsIt() {
        TestCaseGenAgentWorker.Refs refs =
                TestCaseGenAgentWorker.buildRefs("FAT-4021", List.of("MODFIN-391"), EXCLUDE_FAT);
        assertThat(refs.kept()).containsExactly("FAT-4021", "MODFIN-391");
        assertThat(refs.originatingExcluded()).isTrue();
    }

    @Test
    void refsMatchesTheProjectKeyExactlyNotAsAPrefix() {
        assertThat(TestCaseGenAgentWorker.buildRefs("ERM-1", List.of("FATBOY-1"), EXCLUDE_FAT).kept())
                .containsExactly("ERM-1", "FATBOY-1");
    }

    @Test
    void refsReachesTheFrontmatter() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        String plan = worker(model).execute(freshContext()).outputs().get("test_plan.md");

        assertThat(plan).contains("refs: \"UIMARCAUTH-553, MODELINKS-201\"");
    }

    // ── testrail_section is validated, never trusted blindly ────────────────

    private static final String TRIAGE_OK = "CANDIDATES: C900\n";
    private static final String TRIAGE_NONE = "CANDIDATES: none\n";

    /**
     * The trap from the design note: a title that reads as a match for a create story, but whose
     * body is edit-only. Triage must be able to nominate it and the generator must see its body.
     */
    private static TestRailConnector coverageTestRail() {
        return new TestRailConnector() {
            @Override public long addCase(long s, String t, String steps, String exp) { return 0; }
            @Override public long addRun(String n, List<Long> ids) { return 0; }
            @Override public void addResults(long r, Map<Long, Boolean> res, String c) { }
            @Override public List<TestRailSectionRef> getSections() {
                return List.of(new TestRailSectionRef(7L, "MARC Authority \u203a Browse"));
            }
            @Override public List<TestRailCase> getCases(long sectionId) {
                return List.of(new TestRailCase(900L, "Clicking \"Save\" on a record",
                        "An authority record exists and is open in edit mode.\nUser has edit permission.",
                        List.of(new TestRailStep("Click Actions \u2192 Edit", "Edit form opens"),
                                new TestRailStep("Click Save", "Record is updated"))));
            }
        };
    }

    private static TestRailConnector resolvingTestRail() {
        return new TestRailConnector() {
            @Override public long addCase(long s, String t, String steps, String exp) { return 0; }
            @Override public long addRun(String n, List<Long> ids) { return 0; }
            @Override public void addResults(long r, Map<Long, Boolean> res, String c) { }
            @Override public List<String> listSectionPaths() {
                return List.of("MARC Authority › Browse");
            }
            @Override public long resolveSectionByPath(String path) { return 42L; }
        };
    }

    @Test
    void agentSectionIsKeptWhenTestRailCanResolveIt() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        String plan = worker(model, 3, resolvingTestRail()).execute(freshContext())
                .outputs().get("test_plan.md");

        assertThat(plan).contains("testrail_section: \"MARC Authority › Browse\"");
    }

    @Test
    void unresolvableAgentSectionFallsBackToTheConfiguredDefault() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        // UnconfiguredConnectors.TestRail throws on resolveSectionByPath, and no default is set.
        String plan = worker(model).execute(freshContext()).outputs().get("test_plan.md");

        assertThat(plan).contains("testrail_section: \"\"");
    }

    @Test
    void theSectionListReachesTheGenerationPrompt() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model, 3, resolvingTestRail()).execute(freshContext());

        assertThat(model.receivedPrompts().get(1).getContents())
                .contains("MARC Authority › Browse");
    }

    // ── Structural validation (G3) ───────────────────────────────────────────

    @Test
    void malformedPlanTriggersOneFormatCorrectionRetry() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK)
                .enqueue(PLAN_MALFORMED)   // rejected by the parser
                .enqueue(PLAN)             // corrected
                .enqueue(REVIEW_PASS);

        AgentResult result = worker(model).execute(freshContext());

        assertThat(result.outputs()).containsKey("test_plan.md");
        assertThat(model.receivedPrompts()).hasSize(4);
    }

    @Test
    void formatCorrectionRetryTellsTheModelWhatWasWrong() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN_MALFORMED).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        String retryPrompt = model.receivedPrompts().get(2).getContents();
        assertThat(retryPrompt).contains("Output format errors");
        assertThat(retryPrompt).contains("no test cases found");
    }

    @Test
    void malformedTwiceFailsTheStepRatherThanSyncingNothing() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN_MALFORMED).enqueue(PLAN_MALFORMED);

        assertThatThrownBy(() -> worker(model).execute(freshContext()))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("unparseable test plan twice");
    }

    @Test
    void aStepWithABlankExpectedResultIsRejected() {
        String blankExpected = PLAN.replace(
                "   **Expected:** Results list shows Status = \"Active\"", "   **Expected:**");
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(blankExpected).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        assertThat(model.receivedPrompts().get(2).getContents())
                .contains("empty or placeholder expected result");
    }

    // ── Review loop ──────────────────────────────────────────────────────────

    @Test
    void jiraDevTeamOverridesTheTeamTheAgentWrote() {
        // The regression: the agent cannot see the Dev Team field, so it infers a team from
        // the subject matter. The implementing team owns the tests, so Jira must win.
        JiraIssue withTeam = new JiraIssue("UIMARCAUTH-553", "Browse authority records",
                "AC1: browse returns matches.", "Open", "Story", List.of(),
                JsonMapper.builder().build().readTree(
                        "{\"fields\":{\"devTeam\":{\"title\":\"Promin\",\"id\":\"7\"}}}"));
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        String plan = worker(model, new FakeJira(withTeam))
                .execute(freshContext()).outputs().get("test_plan.md");

        // PLAN says "team: Spitfire"; the ticket says Promin.
        assertThat(plan).contains("team: \"Promin\"").doesNotContain("Spitfire");
    }

    @Test
    void theAgentIsToldTheRealTeamSoItDoesNotHaveToGuess() {
        JiraIssue withTeam = new JiraIssue("UIMARCAUTH-553", "Browse authority records",
                "AC1: browse returns matches.", "Open", "Story", List.of(),
                JsonMapper.builder().build().readTree(
                        "{\"fields\":{\"devTeam\":{\"title\":\"Promin\"}}}"));
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model, new FakeJira(withTeam)).execute(freshContext());

        assertThat(model.receivedPrompts().get(1).getContents())
                .contains("**Development Team:** Promin");
    }

    @Test
    void withNoDevTeamOnTheTicketTheAgentsReadingIsUsed() {
        // ISSUE carries no raw payload at all, so the fallback path applies.
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        String plan = worker(model).execute(freshContext()).outputs().get("test_plan.md");

        assertThat(plan).contains("team: \"Spitfire\"");
        assertThat(model.receivedPrompts().get(1).getContents())
                .contains("**Development Team:** not set on the ticket");
    }

    // ── Dev team resolution ────────────────────────────────────────

    private static JiraIssue issueWithDevTeam(String devTeamJson) {
        String raw = """
                {"key":"UIQM-835","fields":{"summary":"s","devTeam":%s}}
                """.formatted(devTeamJson);
        return new JiraIssue("UIQM-835", "s", "d", "Open", "Story", List.of(),
                JsonMapper.builder().build().readTree(raw));
    }

    @Test
    void readsDevTeamFromTheRenamedCustomFieldObject() {
        // JiraRestConnector renames customfield_10057 to devTeam and strips the raw key,
        // so the worker must read the renamed key, not the custom field id.
        assertThat(TestCaseGenAgentWorker.devTeam(
                issueWithDevTeam("{\"title\": \"Promin\", \"id\": \"7\"}"))).isEqualTo("Promin");
    }

    @Test
    void readsDevTeamFromAlternativeSingleSelectShapes() {
        assertThat(TestCaseGenAgentWorker.devTeam(issueWithDevTeam("{\"value\": \"Promin\"}")))
                .isEqualTo("Promin");
        assertThat(TestCaseGenAgentWorker.devTeam(issueWithDevTeam("{\"name\": \"Promin\"}")))
                .isEqualTo("Promin");
        assertThat(TestCaseGenAgentWorker.devTeam(issueWithDevTeam("\"Promin\"")))
                .isEqualTo("Promin");
    }

    @Test
    void missingDevTeamFieldYieldsBlankRatherThanThrowing() {
        assertThat(TestCaseGenAgentWorker.devTeam(issueWithDevTeam("null"))).isEmpty();
        assertThat(TestCaseGenAgentWorker.devTeam(
                new JiraIssue("K-1", "s", "d", "Open", "Story", List.of(), null))).isEmpty();
    }

    // ── Promoted-field stripping ───────────────────────────────────

    @Test
    void stripsTheFenceModelsWrapAroundThePromotedFields() {
        String body = """
                # Analysis

                Some prose.

                ---

                testrail_section: MARC › MARC authority › Create
                team: Thunderjet
                ecs_story: non-ecs

                ---

                # Test Cases

                ## TC001: Something
                """;

        String stripped = TestCaseGenAgentWorker.stripPromotedFields(body);

        assertThat(stripped).doesNotContain("testrail_section")
                .doesNotContain("team:")
                .doesNotContain("ecs_story")
                .doesNotContain("---")
                .contains("Some prose.")
                .contains("## TC001: Something");
    }

    @Test
    void stripsBarePromotedFieldLinesWithoutAFence() {
        String body = """
                # Analysis

                testrail_section: MARC
                team: Thunderjet
                ecs_story: ecs

                # Test Cases
                """;

        assertThat(TestCaseGenAgentWorker.stripPromotedFields(body))
                .doesNotContain("testrail_section")
                .contains("# Analysis")
                .contains("# Test Cases");
    }

    @Test
    void leavesCaseBlocksBelowTheTestCasesHeadingUntouched() {
        // The strip is scoped above `# Test Cases`; a case that legitimately mentions one of
        // the field names in a step must survive verbatim.
        String body = """
                # Analysis

                team: Thunderjet

                # Test Cases

                ## TC001: Something
                1. **Action:** Set team: Thunderjet in the form
                   **Expected:** Saved
                """;

        String stripped = TestCaseGenAgentWorker.stripPromotedFields(body);

        assertThat(stripped).contains("Set team: Thunderjet in the form");
        assertThat(stripped.indexOf("team:")).isGreaterThan(stripped.indexOf("# Test Cases"));
    }

    // ── Prompt caching ───────────────────────────────────────────────────────

    private static String systemMessageOf(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM)
                .map(Message::getText)
                .findFirst()
                .orElseThrow(() -> new AssertionError("prompt has no system message"));
    }

    @Test
    void skillAndExamplesRideInTheSystemMessageSoTheyCanBeCached() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        // Only the system message is cached (SYSTEM_ONLY), so the two large static assets
        // must live there rather than in the user message.
        String system = systemMessageOf(model.receivedPrompts().get(1));
        assertThat(system).contains("SKILL").contains("Golden examples");
    }

    @Test
    void systemMessageIsIdenticalAcrossReviewIterationsSoTheCachePrefixHolds() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK)
                .enqueue(PLAN).enqueue(REVIEW_FAIL)
                .enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        // Prompt caching keys on a byte-identical prefix. Review feedback and the previous
        // plan must stay in the user message; moving any per-attempt value into system.md
        // would silently disable caching, so pin it here.
        String first = systemMessageOf(model.receivedPrompts().get(1));
        String second = systemMessageOf(model.receivedPrompts().get(3));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void chatOptionsRequestSystemPromptCaching() {
        AnthropicChatOptions options =
                (AnthropicChatOptions) worker(new StubChatModel()).chatOptions().build();

        assertThat(options.getMaxTokens()).isEqualTo(16384);
        assertThat(options.getCacheOptions().getStrategy())
                .isEqualTo(AnthropicCacheStrategy.SYSTEM_ONLY);
        assertThat(options.getCacheOptions().getMessageTypeTtl())
                .containsEntry(MessageType.SYSTEM, AnthropicCacheTtl.FIVE_MINUTES);
    }

    @Test
    void effortIsSentOnlyOnOpusTierOrAboveAndOnlyForTheReviewer() {
        for (String capable : List.of("claude-opus-5", "claude-opus-4-8", "claude-fable-5",
                "claude-mythos-5")) {
            assertThat(((AnthropicChatOptions) worker(new StubChatModel(), capable)
                    .chatOptions("cypress-testcase-reviewer").build()).getOutputConfig())
                    .as(capable).isNotNull();
        }

        TestCaseGenAgentWorker opus = worker(new StubChatModel(), "claude-opus-5");
        assertThat(((AnthropicChatOptions) opus.chatOptions("cypress-testcase-gen").build())
                .getOutputConfig()).isNull();

        TestCaseGenAgentWorker sonnet = worker(new StubChatModel(), "claude-sonnet-4-6");
        assertThat(((AnthropicChatOptions) sonnet.chatOptions("cypress-testcase-reviewer").build())
                .getOutputConfig()).isNull();
    }

    @Test
    void theReviewerGetsAHigherCeilingThanGeneration() {
        TestCaseGenAgentWorker worker = worker(new StubChatModel());

        AnthropicChatOptions review =
                (AnthropicChatOptions) worker.chatOptions("cypress-testcase-reviewer").build();
        AnthropicChatOptions generate =
                (AnthropicChatOptions) worker.chatOptions("cypress-testcase-gen").build();

        assertThat(review.getMaxTokens()).isEqualTo(32768);
        assertThat(generate.getMaxTokens()).isEqualTo(16384);
        // Caching still applies to every call, whichever prompt it is.
        assertThat(review.getCacheOptions().getStrategy())
                .isEqualTo(AnthropicCacheStrategy.SYSTEM_ONLY);
    }

    @Test
    void passOnSecondAttemptReportsTwoIterations() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK)
                .enqueue(PLAN).enqueue(REVIEW_FAIL)
                .enqueue(PLAN).enqueue(REVIEW_PASS);

        String plan = worker(model).execute(freshContext()).outputs().get("test_plan.md");

        assertThat(plan).contains("review_passed: true");
        assertThat(plan).contains("review_iterations: 2");
        assertThat(model.receivedPrompts()).hasSize(5);
    }

    @Test
    void reviewFeedbackIsInjectedIntoTheNextGeneration() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK)
                .enqueue(PLAN).enqueue(REVIEW_FAIL)
                .enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        String regen = model.receivedPrompts().get(3).getContents();
        assertThat(regen).contains("Quality gate feedback");
        assertThat(regen).contains("TC001 [R1]");
    }

    @Test
    void feedbackAccumulatesAcrossAttempts() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK)
                .enqueue(PLAN).enqueue(REVIEW_FAIL)
                .enqueue(PLAN).enqueue(REVIEW_FAIL_2)
                .enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        String attempt3 = model.receivedPrompts().get(5).getContents();
        assertThat(attempt3).contains("TC001 [R1]");
        assertThat(attempt3).contains("TC001 [R7]");
    }

    @Test
    void exhaustedReviewStillProducesAPlanForTheGate() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK)
                .enqueue(PLAN).enqueue(REVIEW_FAIL)
                .enqueue(PLAN).enqueue(REVIEW_FAIL)
                .enqueue(PLAN).enqueue(REVIEW_FAIL);

        String plan = worker(model).execute(freshContext()).outputs().get("test_plan.md");

        assertThat(plan).contains("review_passed: false");
        assertThat(plan).contains("review_iterations: 3");
        assertThat(plan).contains("**Result:** FAILED after 3 iteration(s)");
        assertThat(model.receivedPrompts()).hasSize(7);
    }

    @Test
    void theReviewerSeesOnlyTheTestCasesSection() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        String reviewPrompt = model.receivedPrompts().get(2).getContents();
        assertThat(reviewPrompt).contains("## TC001:");
        assertThat(reviewPrompt).doesNotContain("## Coverage matrix");
    }

    // ── Retry run ────────────────────────────────────────────────────────────

    @Test
    void hitlFeedbackAndPreviousPlanReachTheGenerationPrompt() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(retryContext(PLAN, "Cover the empty-query case too."));

        String genPrompt = model.receivedPrompts().get(1).getContents();
        assertThat(genPrompt).contains("QA rejection feedback");
        assertThat(genPrompt).contains("Cover the empty-query case too.");
        assertThat(genPrompt).contains("Previous test plan (rejected");
    }

    // ── Review disabled ──────────────────────────────────────────────────────

    @Test
    void zeroMaxIterationsSkipsTheReviewCall() {
        StubChatModel model = new StubChatModel().enqueue(ROUTER_OK).enqueue(PLAN);

        AgentResult result = worker(model, 0, new UnconfiguredConnectors.TestRail())
                .execute(freshContext());

        assertThat(model.receivedPrompts()).hasSize(2);
        assertThat(result.outputs().get("test_plan.md"))
                .contains("FACTORY_CYPRESS_MAX_REVIEW_ITERATIONS=0");
    }

    // ── Prompt dump (FACTORY_DEBUG_ENABLED) ──────────────────────────────────

    @Test
    void writesNothingWhenDebugIsOff() throws Exception {
        Path dir = Path.of("target", "prompt-dump-off-" + UUID.randomUUID());
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model, 3, new UnconfiguredConnectors.TestRail(), new PromptDump(false, dir))
                .execute(freshContext());

        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void dumpsOneRenderedPromptPerModelCallWhenDebugIsOn() throws Exception {
        Path dir = Path.of("target", "prompt-dump-on-" + UUID.randomUUID());
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK)
                .enqueue(PLAN).enqueue(REVIEW_FAIL)
                .enqueue(PLAN).enqueue(REVIEW_PASS);

        AgentContext ctx = freshContext();
        worker(model, 3, new UnconfiguredConnectors.TestRail(), new PromptDump(true, dir)).execute(ctx);

        Path runDir = dir.resolve(ctx.executionId().toString());
        try (var files = Files.list(runDir)) {
            assertThat(files.map(f -> f.getFileName().toString()).sorted())
                    .containsExactly("1-router.md", "2-generate-attempt1.md", "3-review-attempt1.md",
                            "4-generate-attempt2.md", "5-review-attempt2.md");
        }
    }

    @Test
    void dumpedPromptContainsTheRenderedContentNotThePlaceholders() throws Exception {
        Path dir = Path.of("target", "prompt-dump-content-" + UUID.randomUUID());
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        AgentContext ctx = freshContext();
        worker(model, 3, new UnconfiguredConnectors.TestRail(), new PromptDump(true, dir)).execute(ctx);

        String dumped = Files.readString(
                dir.resolve(ctx.executionId().toString()).resolve("2-generate-attempt1.md"));

        assertThat(dumped).contains("===== SYSTEM =====").contains("===== USER =====");
        assertThat(dumped).contains("Mandatory Workflow");            // SKILL.md was substituted
        assertThat(dumped).contains("AC1: browse returns matches.");  // the raw ticket
        assertThat(dumped).doesNotContain("{{skill}}").doesNotContain("{{issue}}");
    }

    @Test
    void dumpFailureNeverBreaksTheRun() throws Exception {
        // A regular file where the dump wants its root directory: every createDirectories fails.
        Path blocked = Path.of("target", "prompt-dump-blocked-" + UUID.randomUUID());
        Files.createDirectories(blocked.getParent());
        Files.writeString(blocked, "not a directory");

        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        AgentResult result = worker(model, 3, new UnconfiguredConnectors.TestRail(),
                new PromptDump(true, blocked)).execute(freshContext());

        assertThat(result.outputs()).containsKey("test_plan.md");
    }

    // ── Token accounting ─────────────────────────────────────────────────────

    @Test
    void tokenUsageAccumulatesAcrossEveryCall() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK, 40, 20)
                .enqueue(PLAN, 100, 200)
                .enqueue(REVIEW_FAIL, 50, 30)
                .enqueue(PLAN, 120, 210)
                .enqueue(REVIEW_PASS, 60, 25);

        AgentResult result = worker(model).execute(freshContext());

        assertThat(result.metrics().get("promptTokens")).isEqualTo(370L);
        assertThat(result.metrics().get("completionTokens")).isEqualTo(485L);
    }

    // ── Coverage depth: triage + full candidate read ─────────────────────────

    @Test
    void coverageIndexCarriesTruncatedPreconditions() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(TRIAGE_NONE).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model, 3, coverageTestRail()).execute(freshContext());

        assertThat(model.receivedPrompts().get(2).getContents())
                .contains("**C900**")
                .contains("_Preconditions:_ An authority record exists and is open in edit mode.");
    }

    @Test
    void nominatedCandidatesReachTheGenerationPromptInFull() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(TRIAGE_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model, 3, coverageTestRail()).execute(freshContext());

        assertThat(model.receivedPrompts().get(2).getContents())
                .contains("## Candidate cases read in full")
                .contains("**Action:** Click Actions \u2192 Edit")
                .contains("**Expected:** Record is updated");
    }

    @Test
    void triageSeesTheIndexButNotTheCandidateBlock() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(TRIAGE_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model, 3, coverageTestRail()).execute(freshContext());

        String triagePrompt = model.receivedPrompts().get(1).getContents();
        assertThat(triagePrompt).contains("**C900**");
        assertThat(triagePrompt).doesNotContain("## Candidate cases read in full");
    }

    @Test
    void triageIsSkippedWhenThereIsNoCoverage() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue(REVIEW_PASS);

        worker(model).execute(freshContext());

        assertThat(model.receivedPrompts()).hasSize(3);
    }

    @Test
    void aFailedTriageStillProducesAPlan() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue("no candidates line here").enqueue(PLAN).enqueue(REVIEW_PASS);

        AgentResult result = worker(model, 3, coverageTestRail()).execute(freshContext());

        assertThat(result.outputs()).containsKey("test_plan.md");
        assertThat(model.receivedPrompts().get(2).getContents())
                .doesNotContain("## Candidate cases read in full");
    }

    @Test
    void candidateIdsAreParsedFromTheCandidatesLine() {
        assertThat(TestCaseGenAgentWorker.parseCandidateIds("CANDIDATES: C900, C1201, 1500"))
                .containsExactly(900L, 1201L, 1500L);
    }

    @Test
    void noneAndGarbageYieldNoCandidates() {
        assertThat(TestCaseGenAgentWorker.parseCandidateIds("CANDIDATES: none")).isEmpty();
        assertThat(TestCaseGenAgentWorker.parseCandidateIds("I could not decide.")).isEmpty();
    }

    @Test
    void candidateSelectionDropsUnknownIdsAndCaps() {
        CoveragePrefetch.Coverage coverage = new CoveragePrefetch.Coverage("index",
                Map.of(1L, new TestRailCase(1L, "a", "", List.of()),
                        2L, new TestRailCase(2L, "b", "", List.of())),
                Map.of());

        assertThat(coverage.select(List.of(1L, 99L, 2L, 1L), 15)).containsExactly(1L, 2L);
        assertThat(coverage.select(List.of(1L, 2L), 1)).containsExactly(1L);
    }

    // ── Test Group fallbacks are reported, not silent ────────────────────────

    private static TestPlanParser.ParsedCase caseWithTier(String id, String tier) {
        return new TestPlanParser.ParsedCase(id, "t", tier, false, true, false,
                List.of(), List.of(), "");
    }

    @Test
    void aMissingTestGroupIsNamedInTheNotes() {
        assertThat(TestCaseGenAgentWorker.testGroupNotes(
                List.of(caseWithTier("TC001", ""), caseWithTier("TC002", "smoke"))))
                .singleElement().asString()
                .contains("TC001").contains("defaulted to Critical Path");
    }

    @Test
    void anUnrecognisedTestGroupIsNamedWithItsValue() {
        assertThat(TestCaseGenAgentWorker.testGroupNotes(List.of(caseWithTier("TC003", "urgent"))))
                .singleElement().asString()
                .contains("TC003").contains("urgent");
    }

    @Test
    void knownTiersProduceNoNotes() {
        assertThat(TestCaseGenAgentWorker.testGroupNotes(List.of(
                caseWithTier("TC001", "smoke"),
                caseWithTier("TC002", "Critical Path"),
                caseWithTier("TC003", "extended"))))
                .isEmpty();
    }

    @Test
    void factoryNotesLandAtTheEndOfAnalysis() {
        String plan = "# Analysis" + "\n" + "\n" + "Some analysis." + "\n" + "\n"
                + "# Test Cases" + "\n" + "\n" + "## TC001: x" + "\n";

        String out = TestCaseGenAgentWorker.appendFactoryNotes(plan, List.of("something happened"));

        assertThat(out.indexOf("## Factory notes")).isGreaterThan(out.indexOf("Some analysis."));
        assertThat(out.indexOf("## Factory notes")).isLessThan(out.indexOf("# Test Cases"));
        assertThat(out).contains("- something happened");
    }

    @Test
    void noNotesLeavesThePlanUntouched() {
        String plan = "# Analysis" + "\n" + "\n" + "# Test Cases" + "\n";
        assertThat(TestCaseGenAgentWorker.appendFactoryNotes(plan, List.of())).isEqualTo(plan);
    }

    // ── Automation notes never reach the reviewer ────────────────────────────

    @Test
    void automationNotesAreStrippedBeforeReview() {
        String cases = "## TC001: x" + "\n"
                + "**Steps**" + "\n"
                + "1. **Action:** Click Save" + "\n"
                + "   **Expected:** Toast appears" + "\n"
                + "**Automation notes:** marcAuthority.js, edit(), Select('Source')" + "\n";

        String out = TestCaseGenAgentWorker.forReview(cases);

        assertThat(out).doesNotContain("marcAuthority.js").doesNotContain("Select('Source')");
        assertThat(out).contains("**Action:** Click Save").contains("Toast appears");
    }

    @Test
    void aCaseWithoutAutomationNotesIsUnchanged() {
        String cases = "## TC001: x" + "\n" + "1. **Action:** Click Save" + "\n";
        assertThat(TestCaseGenAgentWorker.forReview(cases)).isEqualTo(cases);
    }

    // ── Coverage matrix accounting ───────────────────────────────────────────

    private static String scenarioPlan(String matrix) {
        return "# Analysis" + "\n" + "\n"
                + "## Scenarios" + "\n" + "\n"
                + "1. Defaults appear on create" + "\n"
                + "2. Values survive a save" + "\n"
                + "3. A changed default persists" + "\n" + "\n"
                + matrix
                + "# Test Cases" + "\n" + "\n" + "## TC001: x" + "\n";
    }

    @Test
    void everyScenarioAccountedForProducesNoNote() {
        String matrix = "## Coverage matrix" + "\n" + "\n"
                + "| Scenario | Outcome |" + "\n"
                + "| 1. Defaults | TC001 |" + "\n"
                + "| 2. Save | MERGED into TC001 |" + "\n"
                + "| 3. Changed | TC002 |" + "\n" + "\n";
        assertThat(TestCaseGenAgentWorker.coverageMatrixNotes(scenarioPlan(matrix))).isEmpty();
    }

    @Test
    void aScenarioMissingFromTheMatrixIsNamed() {
        String matrix = "## Coverage matrix" + "\n" + "\n"
                + "| 1. Defaults | TC001 |" + "\n"
                + "| 3. Changed | TC002 |" + "\n" + "\n";
        assertThat(TestCaseGenAgentWorker.coverageMatrixNotes(scenarioPlan(matrix)))
                .singleElement().asString().contains("Scenario(s) 2");
    }

    @Test
    void aMissingMatrixIsReportedWithTheScenarioCount() {
        assertThat(TestCaseGenAgentWorker.coverageMatrixNotes(scenarioPlan("")))
                .singleElement().asString().contains("3 numbered scenario");
    }

    @Test
    void anAnalysisReplacedByAFixSummaryIsFlagged() {
        // What a re-generated plan looked like: the whole analysis swapped for a changelog.
        assertThat(TestCaseGenAgentWorker.coverageMatrixNotes(
                "# Analysis" + "\n" + "\n"
                        + "## Fix applied (attempt 3)" + "\n" + "\n"
                        + "TC002 step 3 reworded." + "\n" + "\n"
                        + "# Test Cases" + "\n"))
                .singleElement().asString().contains("No `## Scenarios`");
    }

    // ── Empty headings left by the promoted-field strip ──────────────────────

    @Test
    void anEmptyHeadingLeftByStrippingIsRemoved() {
        String body = "# Analysis" + "\n" + "\n"
                + "## Metadata fields" + "\n" + "\n"
                + "testrail_section: A > B" + "\n"
                + "team: Promin" + "\n"
                + "ecs_story: non-ecs" + "\n" + "\n"
                + "---" + "\n" + "\n"
                + "# Test Cases" + "\n" + "## TC001: x" + "\n";

        String out = TestCaseGenAgentWorker.stripPromotedFields(body);

        assertThat(out).doesNotContain("## Metadata fields").doesNotContain("team: Promin");
        assertThat(out).contains("# Test Cases").contains("## TC001: x");
    }

    @Test
    void aHeadingWithRealContentSurvives() {
        String body = "# Analysis" + "\n" + "\n"
                + "## Story summary" + "\n" + "\n"
                + "It changes defaults." + "\n" + "\n"
                + "# Test Cases" + "\n";
        assertThat(TestCaseGenAgentWorker.stripPromotedFields(body))
                .contains("## Story summary").contains("It changes defaults.");
    }

    // ── An empty reviewer response must not burn the retry budget ────────────

    @Test
    void anEmptyReviewStopsTheLoopInsteadOfRegenerating() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue("");

        AgentResult result = worker(model).execute(freshContext());

        // router + generate + review = 3. A 4th call would mean it regenerated on an empty review.
        assertThat(model.receivedPrompts()).hasSize(3);
        assertThat(result.outputs().get("test_plan.md")).contains("NOT CHECKED");
    }

    @Test
    void anEmptyReviewSaysTheCasesWereNotChecked() {
        StubChatModel model = new StubChatModel()
                .enqueue(ROUTER_OK).enqueue(PLAN).enqueue("");

        String plan = worker(model).execute(freshContext()).outputs().get("test_plan.md");

        assertThat(plan)
                .contains("returned no usable response")
                .contains("Review them manually")
                .doesNotContain("Remaining issues");
        assertThat(plan).contains("review_passed: false");
    }
}
