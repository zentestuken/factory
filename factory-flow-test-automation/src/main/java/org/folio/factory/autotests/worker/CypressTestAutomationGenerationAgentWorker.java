package org.folio.factory.autotests.worker;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.autotests.cli.CodingAgentCli;
import org.folio.factory.autotests.workspace.AutomationWorkspace;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/** Writes the spec (and any fragment changes) for one case with the claude CLI, in the automation workspace. */
public class CypressTestAutomationGenerationAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-automate-test-case";
    /** No git: the pipeline owns branches and commits. */
    static final List<String> TOOLS = List.of("Read", "Edit", "Write", "Glob", "Grep");
    private static final JsonMapper JSON = new JsonMapper();

    private final CodingAgentCli cli;
    private final AutomationWorkspace workspace;

    public CypressTestAutomationGenerationAgentWorker(ChatClient chatClient, CodingAgentCli cli,
                                                      AutomationWorkspace workspace) {
        super(chatClient);
        this.cli = cli;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        JsonNode testCase = JSON.readTree(context.requireInput("testCaseDetails").content());
        JsonNode relatedFiles = JSON.readTree(context.requireInput("relatedFiles").content());
        return AgentResult.of("generationSummary", automate(testCase, relatedFiles, ""));
    }

    /** @param guidance case-specific notes from the pipeline (dev team, author notes, gate 2 feedback), or blank */
    public String automate(JsonNode testCase, JsonNode relatedFiles, String guidance) {
        Map<String, Object> variables = Map.of("testCase", testCase, "relatedFiles", relatedFiles,
                "testCaseId", caseId(testCase));
        String prompt = systemPrompt(variables) + "\n" + userPrompt(variables)
                + "\n\nFormatting and lint (prettier, eslint --fix) run in the pipeline after the review - "
                + "do not run them yourself.";
        if (guidance != null && !guidance.isBlank()) {
            prompt += "\n\n## Notes for this test case - follow them\n\n" + guidance;
        }
        return cli.run(prompt, workspace.root(), TOOLS);
    }

    static String caseId(JsonNode testCase) {
        return "C" + testCase.path("id").asString("");
    }
}
