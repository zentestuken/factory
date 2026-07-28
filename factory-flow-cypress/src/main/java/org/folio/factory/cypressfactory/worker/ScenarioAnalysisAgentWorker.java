package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

/**
 * Produces {@code scenario_list.md}: a categorized, human-reviewable list of proposed
 * test scenarios before any test cases are written. The downstream HITL gate lets QA
 * confirm, add, or remove scenarios; the spec agent then generates exactly the confirmed set.
 */
public class ScenarioAnalysisAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-scenario-analysis";

    public ScenarioAnalysisAgentWorker(ChatClient chatClient) {
        super(chatClient);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String scopeManifest = context.requireInput("scope_manifest.md").content();
        String ctx = context.requireInput("context.md").content();
        String scenarioList = callForText(Map.of("scope_manifest", scopeManifest, "context", ctx));
        return AgentResult.of("scenario_list.md", scenarioList);
    }
}
