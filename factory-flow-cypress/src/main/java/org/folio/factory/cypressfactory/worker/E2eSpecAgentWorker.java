package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.cypressfactory.model.CypressTestPlan;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a Cypress E2E test plan from the approved scope manifest plus area context.
 * Outputs {@code test_plan.md} with lightweight YAML frontmatter and a human-readable body
 * that is the single source of truth for case data (title, steps, tier, etc.).
 */
public class E2eSpecAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-e2e-spec-agent";

    private final FrontmatterCodec frontmatterCodec;

    public E2eSpecAgentWorker(ChatClient chatClient, FrontmatterCodec frontmatterCodec) {
        super(chatClient);
        this.frontmatterCodec = frontmatterCodec;
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

        CypressTestPlan plan = callForEntity(
                Map.of("scope_manifest", scopeManifest, "context", ctx,
                        "fragment_library", fragments, "examples", examples,
                        "scenario_list", scenarioList,
                        "existing_coverage", existingCoverage),
                CypressTestPlan.class);

        if (plan.cases().isEmpty()) {
            throw new AgentExecutionException("E2E spec agent produced no test cases");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issue_key", plan.issueKey());
        metadata.put("team", plan.team());
        metadata.put("ecs_enabled", plan.ecsEnabled());
        metadata.put("case_count", plan.cases().size());

        return resultWithUsage("test_plan.md", frontmatterCodec.render(metadata, renderBody(plan)));
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
                tc.preconditions().forEach(p -> body.append("- ").append(p).append("\n"));
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
}
