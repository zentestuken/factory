package org.folio.factory.autotests.worker;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.autotests.TestAutomationFlowProperties;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 *
 */
public class CypressDraftTestAutomationAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-draft-automate-test-case";


    public CypressDraftTestAutomationAgentWorker(ChatClient chatClient,
                                                 TestAutomationFlowProperties properties) {
        super(chatClient);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String testCaseDetails = context.requireInput("testCaseDetails").content();
        JsonNode testCase = new JsonMapper().readTree(testCaseDetails);

        JsonNode relatedFiles = new JsonMapper().readTree(context.requireInput("relatedFiles").content());

        String draftTestScript = callForEntity(Map.of(
                "testCase", testCase, "relatedFiles", relatedFiles), String.class);

        return resultWithUsage("draftTestScript", draftTestScript);
    }
}
