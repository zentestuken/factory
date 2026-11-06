package org.folio.factory.autotests.worker;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class CypressTestAutomationReviewAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-review-automated-test-case";

    private final String anthropicApiKey;

    public CypressTestAutomationReviewAgentWorker(ChatClient chatClient) {
        super(chatClient);
        this.anthropicApiKey = System.getenv("ANTHROPIC_API_KEY");
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
        String generationSummary = context.requireInput("generationSummary").content();

        Map<String, Object> variables = Map.of("testCase", testCase, "relatedFiles", relatedFiles, "generationSummary", generationSummary);

        String system = systemPrompt(variables);
        String user = userPrompt(variables);



        String claudePrompt = system + "\n" + user;

        Path workspace = Path.of(System.getProperty("user.dir"), "cypressTafClone");
        String reviewSummary = runClaude(claudePrompt, workspace);

        return AgentResult.of("reviewSummary", reviewSummary);
    }

    // --allowedTools: whitelists Read/Write/Edit/Glob/Grep only — no permission prompts for these, everything else denied
    // --add-dir: grants file access to cypressTafClone (absolute path required)
    // --print: headless mode, reads prompt from stdin (redirected from temp file to avoid cmd.exe 8191-char limit)
    // ANTHROPIC_API_KEY is inherited from the parent JVM process environment
    private String runClaude(String prompt, Path workDir) {
        Path promptFile = null;
        try {
            promptFile = Files.createTempFile("claude-prompt-", ".txt");
            Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                    "cmd.exe", "/c", "claude",
                    "--print",
                    "--allowedTools", "Read,Edit,Write,Glob,Grep,Bash(git status:*),Bash(git add:*),Bash(git diff:*),Bash(npx cypress run:*)",
                    "--add-dir", workDir.toAbsolutePath().toString())
                    .directory(workDir.toFile())
                    .redirectInput(promptFile.toFile())
                    .redirectErrorStream(true);
            pb.environment().put("ANTHROPIC_API_KEY", anthropicApiKey);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("claude exited with code " + exitCode + ": " + output);
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for claude", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to run claude process", e);
        } finally {
            if (promptFile != null) {
                try { Files.deleteIfExists(promptFile); } catch (IOException ignored) {}
            }
        }
    }
}
