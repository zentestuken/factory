package org.folio.factory.autotests.worker;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.autotests.cli.CodingAgentCli;
import org.folio.factory.autotests.workspace.AutomationWorkspace;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reviews (and may fix) the generated spec with the claude CLI; optionally runs it. Never commits. */
public class CypressTestAutomationReviewAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-review-automated-test-case";
    private static final JsonMapper JSON = new JsonMapper();

    private final CodingAgentCli cli;
    private final AutomationWorkspace workspace;
    private final boolean runSpecs;

    public CypressTestAutomationReviewAgentWorker(ChatClient chatClient, CodingAgentCli cli,
                                                  AutomationWorkspace workspace, boolean runSpecs) {
        super(chatClient);
        this.cli = cli;
        this.workspace = workspace;
        this.runSpecs = runSpecs;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        JsonNode testCase = JSON.readTree(context.requireInput("testCaseDetails").content());
        JsonNode relatedFiles = JSON.readTree(context.requireInput("relatedFiles").content());
        String generationSummary = context.requireInput("generationSummary").content();
        return AgentResult.of("reviewSummary", review(testCase, relatedFiles, generationSummary));
    }

    public String review(JsonNode testCase, JsonNode relatedFiles, String generationSummary) {
        Map<String, Object> variables = Map.of("testCase", testCase, "relatedFiles", relatedFiles,
                "generationSummary", generationSummary,
                "TEST_CASE_ID", CypressTestAutomationGenerationAgentWorker.caseId(testCase));
        String prompt = systemPrompt(variables) + "\n" + userPrompt(variables);
        if (!runSpecs) {
            prompt += "\n\nRunning specs is disabled for this run (FACTORY_CYPRESS_AUTOMATION_RUN_SPECS=false): "
                    + "do not attempt `npx cypress run` or `npx eslint`; review statically and report "
                    + "`\"ran\": false`. Formatting and lint are handled by the pipeline after your review.";
        }
        return cli.run(prompt, workspace.root(), tools(runSpecs));
    }

    static List<String> tools(boolean runSpecs) {
        List<String> tools = new ArrayList<>(List.of("Read", "Edit", "Write", "Glob", "Grep",
                "Bash(git status:*)", "Bash(git add:*)", "Bash(git diff:*)"));
        if (runSpecs) tools.add("Bash(npx cypress run:*)");
        return tools;
    }
}
