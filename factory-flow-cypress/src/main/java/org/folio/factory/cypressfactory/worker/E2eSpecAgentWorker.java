package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.cypressfactory.model.CypressTestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class E2eSpecAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-e2e-spec-agent";
    private static final String REVIEWER_PROMPT_ID = "cypress-testcase-reviewer";
    private static final Logger log = LoggerFactory.getLogger(E2eSpecAgentWorker.class);

    private final FrontmatterCodec frontmatterCodec;
    private final int maxReviewIterations;

    public E2eSpecAgentWorker(ChatClient chatClient, FrontmatterCodec frontmatterCodec, int maxReviewIterations) {
        super(chatClient);
        this.frontmatterCodec = frontmatterCodec;
        this.maxReviewIterations = maxReviewIterations;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected Integer maxTokens() {
        return 16384;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String scopeManifest = context.requireInput("scope_manifest.md").content();
        String ctx = context.requireInput("context.md").content();
        ArtifactContent fragmentLib = context.inputs().get("fragment_library.md");
        String fragments = fragmentLib != null ? fragmentLib.content() : "";
        ArtifactContent examplesArtifact = context.inputs().get("examples.md");
        String examples = examplesArtifact != null ? examplesArtifact.content() : "";
        ArtifactContent scenarioListArtifact = context.inputs().get("scenario_list.md");
        String scenarioList = scenarioListArtifact != null ? scenarioListArtifact.content() : "";
        ArtifactContent coverageArtifact = context.inputs().get("existing_coverage.md");
        String existingCoverage = coverageArtifact != null ? coverageArtifact.content() : "";

        Map<String, Object> vars = new HashMap<>(Map.of(
                "scope_manifest", scopeManifest,
                "context", ctx,
                "fragment_library", fragments,
                "examples", examples,
                "scenario_list", scenarioList,
                "existing_coverage", existingCoverage,
                "review_feedback", ""));
        vars.put("previous_attempt", "");

        long[] totalUsage = {0, 0};
        CypressTestPlan plan;
        ReviewFeedback feedback;
        int attempt = 0;

        if (maxReviewIterations == 0) {
            log.info("[{}] Generating test cases (agent review disabled)", ID);
            plan = callForEntity(vars, CypressTestPlan.class);
            accumulate(totalUsage, drainUsage());
            if (plan.cases().isEmpty()) throw new AgentExecutionException("E2E spec agent produced no test cases");
            log.info("[{}] Generated {} test case(s)", ID, plan.cases().size());
            feedback = new ReviewFeedback(true, "", "Agent review disabled (FACTORY_CYPRESS_MAX_REVIEW_ITERATIONS=0)");
        } else {
            do {
                attempt++;
                log.info("[{}] Generating test cases — attempt {}/{}", ID, attempt, maxReviewIterations);
                plan = callForEntity(vars, CypressTestPlan.class);
                accumulate(totalUsage, drainUsage());
                if (plan.cases().isEmpty()) throw new AgentExecutionException("E2E spec agent produced no test cases");
                log.info("[{}] Generated {} test case(s), running quality review...", ID, plan.cases().size());
                feedback = reviewTestCases(renderBody(plan), scopeManifest, attempt);
                accumulate(totalUsage, drainUsage());
                if (feedback.passed()) {
                    log.info("[{}] Quality review PASSED on attempt {}/{}", ID, attempt, maxReviewIterations);
                    break;
                }
                long issueCount = feedback.issuesBlock().lines()
                        .filter(l -> l.strip().startsWith("-")).count();
                log.info("[{}] Quality review FAILED on attempt {}/{} ({} issue(s)) — retrying",
                        ID, attempt, maxReviewIterations, issueCount);
                String prior = (String) vars.get("review_feedback");
                String block = buildFeedbackBlock(feedback.issuesBlock(), attempt);
                vars.put("review_feedback", prior.isBlank() ? block : prior + "\n\n" + block);
                vars.put("previous_attempt", buildPreviousAttemptSection(renderBody(plan), attempt));
            } while (attempt < maxReviewIterations);
            if (!feedback.passed()) {
                log.warn("[{}] Quality gate exhausted after {}/{} attempt(s) — passing to HITL gate with remaining issues",
                        ID, attempt, maxReviewIterations);
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issue_key", plan.issueKey());
        metadata.put("team", plan.team());
        metadata.put("ecs_enabled", plan.ecsEnabled());
        metadata.put("case_count", plan.cases().size());

        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("test_plan.md", frontmatterCodec.render(metadata, renderBody(plan)));
        outputs.put("testcase_review.md", renderReviewArtifact(plan.issueKey(), feedback, attempt));

        Map<String, Object> metrics = totalUsage[0] + totalUsage[1] > 0
                ? Map.of("promptTokens", totalUsage[0], "completionTokens", totalUsage[1])
                : Map.of();
        return new AgentResult(outputs, metrics);
    }

    private ReviewFeedback reviewTestCases(String renderedPlan, String scopeManifest, int attempt) {
        Map<String, Object> vars = Map.of(
                "test_cases", renderedPlan,
                "scope_manifest", scopeManifest,
                "attempt", attempt,
                "max_attempts", maxReviewIterations);
        String result = callForText(REVIEWER_PROMPT_ID, vars);
        boolean passed = result.contains("REVIEW_RESULT: PASS");
        String issues = passed ? "" : extractIssuesBlock(result);
        return new ReviewFeedback(passed, issues, result);
    }

    private String buildFeedbackBlock(String issuesBlock, int attempt) {
        return "\n## Quality gate feedback — attempt " + attempt + " of " + maxReviewIterations + "\n\n"
                + "The previous version failed the automated quality review. "
                + "**Edit the previous version shown below — fix only the flagged steps; do not change steps or cases that had no issues.**\n\n"
                + "Banned patterns found (must not appear anywhere — not just in the listed instances):\n\n"
                + issuesBlock + "\n\n"
                + "Rule reminders:\n"
                + "- R1: Banned phrases: \"works correctly\", \"confirming\", \"is successful\", bare \"is displayed\" without element name. "
                + "Navigation steps that name the destination (\"Package detail record opens\") are NOT R1 violations.\n"
                + "- R2: Toast/message/notification → exact quoted text or \"(verify exact wording on first execution)\"\n"
                + "- R3: Numeric assertions → absolute final value, never \"decreased accordingly\" or \"updated accordingly\"\n"
                + "- R4: Two or more Field = value pairs → `\\n- Field = value` bullet list, never joined inline\n"
                + "- R6: Step actions → imperative style, never \"User clicks\"\n"
                + "- R7: Every step must have a non-empty expected result\n"
                + "- R10: At least one step per case must assert a concrete domain value, not only navigation\n";
    }

    private String buildPreviousAttemptSection(String renderedPlan, int attempt) {
        return "\n## Previous version (attempt " + attempt + ") — edit this, do not regenerate from scratch\n\n"
                + "Fix only the steps listed in the quality gate feedback above. "
                + "All other cases and steps must be reproduced unchanged.\n\n"
                + renderedPlan;
    }

    private String extractIssuesBlock(String reviewText) {
        int failIdx = reviewText.indexOf("REVIEW_RESULT: FAIL");
        int searchFrom = failIdx >= 0 ? failIdx : 0;
        int issuesIdx = reviewText.indexOf("Issues:", searchFrom);
        return issuesIdx >= 0 ? reviewText.substring(issuesIdx) : reviewText.substring(searchFrom);
    }

    private String renderReviewArtifact(String issueKey, ReviewFeedback feedback, int iterations) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("review_passed", feedback.passed());
        meta.put("iterations", iterations);

        StringBuilder body = new StringBuilder("# Test Case Review — ").append(issueKey).append("\n\n");
        if (feedback.passed()) {
            body.append("**Result:** PASSED after ").append(iterations).append(" iteration(s)\n\n");
            body.append(feedback.fullText());
        } else {
            body.append("**Result:** FAILED after ").append(iterations).append(" iteration(s)\n\n");
            body.append("Remaining issues (amend test_plan.md at gate-1):\n\n");
            body.append(feedback.issuesBlock());
        }

        return frontmatterCodec.render(meta, body.toString());
    }

    private static void accumulate(long[] total, long[] delta) {
        total[0] += delta[0];
        total[1] += delta[1];
    }

    private String renderBody(CypressTestPlan plan) {
        StringBuilder body = new StringBuilder("# Cypress Test Plan — ").append(plan.issueKey()).append("\n");
        for (CypressTestPlan.TestCase tc : plan.cases()) {
            body.append("\n## ").append(tc.id()).append(": ").append(tc.title()).append("\n\n")
                    .append("- **Test Group:** ").append(tc.testGroup()).append("\n")
                    .append("- **User Journey:** ").append(tc.userJourney() ? "Yes" : "No").append("\n")
                    .append("- **Automatable:** ").append(tc.automatable() ? "Yes" : "No").append("\n")
                    .append("- **ECS:** ").append(tc.ecsEnabled() ? "Yes" : "No").append("\n");
            if (!tc.preconditions().isEmpty()) {
                body.append("\n**Preconditions**\n");
                for (String p : tc.preconditions()) {
                    if (!p.contains("\n")) {
                        body.append("- ").append(p).append("\n");
                    } else {
                        String[] parts = p.split("\n", -1);
                        body.append("- ").append(parts[0]).append("\n");
                        for (int j = 1; j < parts.length; j++) {
                            body.append("  ").append(parts[j]).append("\n");
                        }
                    }
                }
            }
            body.append("\n**Steps**\n");
            List<CypressTestPlan.Step> steps = tc.steps();
            for (int i = 0; i < steps.size(); i++) {
                CypressTestPlan.Step step = steps.get(i);
                body.append(i + 1).append(". **Action:** ").append(step.action()).append("\n")
                        .append("   **Expected:** ").append(step.expected()).append("\n");
            }
            if (!tc.automationNotes().isBlank()) {
                body.append("\n**Automation notes:** ").append(tc.automationNotes()).append("\n");
            }
        }
        return body.toString();
    }

    private record ReviewFeedback(boolean passed, String issuesBlock, String fullText) {}
}
