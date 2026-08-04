package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

/**
 * Produces {@code scenario_list.md}: a categorized, human-reviewable list of proposed
 * test scenarios before any test cases are written. The downstream HITL gate lets QA
 * confirm, add, or remove scenarios; the spec agent then generates exactly the confirmed set.
 *
 * Also suggests a {@code testrail_section} path in the frontmatter by matching the story
 * area against the live TestRail section tree (fetched at runtime; empty when unconfigured).
 */
public class ScenarioAnalysisAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-scenario-analysis";

    private final TestRailConnector testRail;

    public ScenarioAnalysisAgentWorker(ChatClient chatClient, TestRailConnector testRail) {
        super(chatClient);
        this.testRail = testRail;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String scopeManifest = context.requireInput("scope_manifest.md").content();
        String ctx = context.requireInput("context.md").content();
        var coverageArtifact = context.inputs().get("existing_coverage.md");
        String existingCoverage = coverageArtifact != null ? coverageArtifact.content() : "";

        String scenarioList = callForText(Map.of(
                "scope_manifest", scopeManifest,
                "context", ctx,
                "sections", buildSectionsBlock(),
                "existing_coverage", existingCoverage.isBlank()
                        ? "_No existing coverage data available._"
                        : existingCoverage));
        return AgentResult.of("scenario_list.md", scenarioList);
    }

    private String buildSectionsBlock() {
        try {
            List<String> paths = testRail.listSectionPaths();
            if (paths.isEmpty()) {
                return "(TestRail not configured — suggest the section path based on the issue area)";
            }
            return String.join("\n", paths);
        } catch (Exception e) {
            return "(TestRail sections unavailable — suggest the section path based on the issue area)";
        }
    }
}
