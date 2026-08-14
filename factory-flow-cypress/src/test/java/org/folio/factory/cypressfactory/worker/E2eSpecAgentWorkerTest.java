package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.agents.llm.StubChatModel;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class E2eSpecAgentWorkerTest {

    private static final String PLAN_JSON_PASS = """
            {
              "issueKey": "ERM-1",
              "team": "Folijet",
              "ecsEnabled": false,
              "cases": [
                {
                  "id": "TC001",
                  "title": "Create agreement with valid data",
                  "automatable": true,
                  "testGroup": "critical",
                  "userJourney": false,
                  "ecsEnabled": false,
                  "preconditions": ["User is logged in as admin"],
                  "steps": [
                    {
                      "action": "Navigate to Agreements",
                      "expected": "Agreements list is displayed"
                    },
                    {
                      "action": "Click New",
                      "expected": "Agreement form opens with Status = \\"Draft\\""
                    }
                  ],
                  "automationNotes": ""
                }
              ]
            }
            """;

    private static final String REVIEW_PASS = """
            REVIEW_RESULT: PASS
            All 1 cases pass all 9 checklist rules.
            """;

    private static final String REVIEW_FAIL = """
            REVIEW_RESULT: FAIL
            Issues:
            - TC001 [R1] Step 1 expected: "Agreements list is displayed" — vague, no concrete element name
            """;

    private static final String REVIEW_FAIL_2 = """
            REVIEW_RESULT: FAIL
            Issues:
            - TC001 [R7] Step 2 expected: "" — empty expected result
            """;

    // Reviewer emits a reasoning block before the real verdict (format violation, but must be handled)
    private static final String REVIEW_FAIL_WITH_PREAMBLE = """
            Issues:
            - TC001 [R2] Step 1 — quoted text present but no (verify exact wording) note; however this satisfies R2. (No violation.)
            REVIEW_RESULT: FAIL
            Issues:
            - TC001 [R1] Step 1 expected: "list is displayed" — vague, no concrete element name
            """;

    private final FrontmatterCodec codec = new FrontmatterCodec();

    private E2eSpecAgentWorker worker(StubChatModel model) {
        return new E2eSpecAgentWorker(ChatClient.create(model), codec, 3);
    }

    private E2eSpecAgentWorker workerWithMaxIterations(StubChatModel model, int max) {
        return new E2eSpecAgentWorker(ChatClient.create(model), codec, max);
    }

    private AgentContext context() {
        return new AgentContext(UUID.randomUUID(), "test-spec",
                Map.of(
                        "scope_manifest.md", new ArtifactContent("scope_manifest.md", 1, "text/markdown", "issue_key: ERM-1"),
                        "context.md", new ArtifactContent("context.md", 1, "text/markdown", "# Context")),
                null, Map.of(), List.of("test_plan.md", "testcase_review.md"));
    }

    // -------------------------------------------------------------------------
    // Single pass — PASS on first review
    // -------------------------------------------------------------------------

    @Test
    void passOnFirstReview_producesBothArtifacts() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS)   // generation call 1
                .enqueue(REVIEW_PASS);     // review call 1

        AgentResult result = worker(model).execute(context());

        assertThat(result.outputs()).containsKeys("test_plan.md", "testcase_review.md");
        assertThat(result.outputs().get("test_plan.md")).contains("TC001");
    }

    @Test
    void passOnFirstReview_reviewArtifactReportsPassedOneIteration() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS)
                .enqueue(REVIEW_PASS);

        AgentResult result = worker(model).execute(context());

        String review = result.outputs().get("testcase_review.md");
        assertThat(review).contains("review_passed: true");
        assertThat(review).contains("iterations: 1");
        assertThat(review).contains("PASSED after 1 iteration(s)");
    }

    @Test
    void passOnFirstReview_usesTwoLlmCalls() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS)
                .enqueue(REVIEW_PASS);

        worker(model).execute(context());

        assertThat(model.receivedPrompts()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Retry on FAIL — PASS on second attempt
    // -------------------------------------------------------------------------

    @Test
    void passOnSecondAttempt_usesCorrectIterationCount() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS)   // generation 1
                .enqueue(REVIEW_FAIL)      // review 1 → FAIL
                .enqueue(PLAN_JSON_PASS)   // generation 2
                .enqueue(REVIEW_PASS);     // review 2 → PASS

        AgentResult result = worker(model).execute(context());

        String review = result.outputs().get("testcase_review.md");
        assertThat(review).contains("review_passed: true");
        assertThat(review).contains("iterations: 2");
        assertThat(model.receivedPrompts()).hasSize(4);
    }

    @Test
    void failedReviewFeedbackIsInjectedIntoNextGenerationPrompt() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS)
                .enqueue(REVIEW_FAIL)
                .enqueue(PLAN_JSON_PASS)
                .enqueue(REVIEW_PASS);

        worker(model).execute(context());

        // The 3rd prompt (index 2) is the re-generation call — its user content
        // must contain the feedback block and the issues from the failed review.
        String regenUserPrompt = model.receivedPrompts().get(2).getContents();
        assertThat(regenUserPrompt).contains("Quality gate feedback");
        assertThat(regenUserPrompt).contains("TC001 [R1]");
    }

    @Test
    void reviewWithReasoningPreamble_extractsRealIssuesBlockNotPreamble() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS)
                .enqueue(REVIEW_FAIL_WITH_PREAMBLE)
                .enqueue(PLAN_JSON_PASS)
                .enqueue(REVIEW_PASS);

        worker(model).execute(context());

        // Re-gen prompt must contain the real R1 issue (from post-REVIEW_RESULT block),
        // not the preamble's "(No violation.)" text
        String regenUserPrompt = model.receivedPrompts().get(2).getContents();
        assertThat(regenUserPrompt).contains("TC001 [R1]");
        assertThat(regenUserPrompt).doesNotContain("No violation");
    }

    // -------------------------------------------------------------------------
    // Max iterations — still FAIL after 3 attempts
    // -------------------------------------------------------------------------

    @Test
    void allIterationsFail_reviewArtifactReportsFailedThreeIterations() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS).enqueue(REVIEW_FAIL)  // attempt 1
                .enqueue(PLAN_JSON_PASS).enqueue(REVIEW_FAIL)  // attempt 2
                .enqueue(PLAN_JSON_PASS).enqueue(REVIEW_FAIL); // attempt 3

        AgentResult result = worker(model).execute(context());

        String review = result.outputs().get("testcase_review.md");
        assertThat(review).contains("review_passed: false");
        assertThat(review).contains("iterations: 3");
        assertThat(review).contains("FAILED after 3 iteration(s)");
        assertThat(model.receivedPrompts()).hasSize(6);
    }

    @Test
    void allIterationsFail_remainingIssuesAppearsInReviewArtifact() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS).enqueue(REVIEW_FAIL)
                .enqueue(PLAN_JSON_PASS).enqueue(REVIEW_FAIL)
                .enqueue(PLAN_JSON_PASS).enqueue(REVIEW_FAIL);

        AgentResult result = worker(model).execute(context());

        assertThat(result.outputs().get("testcase_review.md"))
                .contains("Remaining issues")
                .contains("TC001 [R1]");
    }

    // -------------------------------------------------------------------------
    // Feedback accumulation across iterations
    // -------------------------------------------------------------------------

    @Test
    void allPriorFeedbacksAccumulateIntoThirdAttemptPrompt() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS).enqueue(REVIEW_FAIL)    // attempt 1: issues from REVIEW_FAIL
                .enqueue(PLAN_JSON_PASS).enqueue(REVIEW_FAIL_2)  // attempt 2: different issues from REVIEW_FAIL_2
                .enqueue(PLAN_JSON_PASS).enqueue(REVIEW_PASS);   // attempt 3: pass

        worker(model).execute(context());

        // The 5th prompt (index 4) is the attempt-3 generation call.
        // It must contain issues from BOTH attempt 1 AND attempt 2.
        String attempt3Prompt = model.receivedPrompts().get(4).getContents();
        assertThat(attempt3Prompt).contains("TC001 [R1]");  // from attempt 1 feedback
        assertThat(attempt3Prompt).contains("TC001 [R7]");  // from attempt 2 feedback
    }

    @Test
    void secondAttemptPromptContainsOnlyFirstIterationFeedback() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS).enqueue(REVIEW_FAIL)
                .enqueue(PLAN_JSON_PASS).enqueue(REVIEW_PASS);

        worker(model).execute(context());

        // The 3rd prompt (index 2) is the attempt-2 generation call.
        String attempt2Prompt = model.receivedPrompts().get(2).getContents();
        assertThat(attempt2Prompt).contains("TC001 [R1]");
        assertThat(attempt2Prompt).contains("Quality gate feedback — attempt 1 of 3");
    }

    // -------------------------------------------------------------------------
    // Token accumulation
    // -------------------------------------------------------------------------

    @Test
    void tokenUsageIsAccumulatedAcrossAllCalls() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS, 100, 200)  // gen1: 100+200
                .enqueue(REVIEW_FAIL, 50, 30)       // review1: 50+30
                .enqueue(PLAN_JSON_PASS, 120, 210)  // gen2: 120+210
                .enqueue(REVIEW_PASS, 60, 25);      // review2: 60+25

        AgentResult result = worker(model).execute(context());

        assertThat(result.metrics().get("promptTokens")).isEqualTo(330L);      // 100+50+120+60
        assertThat(result.metrics().get("completionTokens")).isEqualTo(465L);  // 200+30+210+25
    }

    // -------------------------------------------------------------------------
    // maxReviewIterations = 0 — agent review disabled
    // -------------------------------------------------------------------------

    @Test
    void zeroMaxIterations_skipsReviewAndProducesBothArtifacts() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS);  // only 1 LLM call — no review

        AgentResult result = workerWithMaxIterations(model, 0).execute(context());

        assertThat(model.receivedPrompts()).hasSize(1);
        assertThat(result.outputs()).containsKeys("test_plan.md", "testcase_review.md");
        assertThat(result.outputs().get("test_plan.md")).contains("TC001");
    }

    @Test
    void zeroMaxIterations_reviewArtifactReportsZeroIterations() {
        StubChatModel model = new StubChatModel()
                .enqueue(PLAN_JSON_PASS);

        AgentResult result = workerWithMaxIterations(model, 0).execute(context());

        String review = result.outputs().get("testcase_review.md");
        assertThat(review).contains("review_passed: true");
        assertThat(review).contains("iterations: 0");
        assertThat(review).contains("FACTORY_CYPRESS_MAX_REVIEW_ITERATIONS=0");
    }
}
