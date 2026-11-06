package org.folio.factory.autotests.worker;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;


import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import java.io.IOException;
import java.nio.file.Files;

public class CypressTestFrameworkExplorerAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-test-framework-explorer";

    public CypressTestFrameworkExplorerAgentWorker(ChatClient chatClient) {
        super(chatClient);
    }

    @Override
    public String id() {
        return ID;
    }

    public List<Path> collectFiles(Path root) {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !containsSegment(p, "node_modules"))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean containsSegment(Path path, String segment) {
        for (Path part : path) {
            if (part.toString().equals(segment)) return true;
        }
        return false;
    }


    @Override
    public AgentResult execute(AgentContext context) {
        List<Path> files = collectFiles(Path.of("cypressTafClone/cypress"));

        String testCaseDetails = context.requireInput("testCaseDetails").content();
        JsonNode testCase = new JsonMapper().readTree(testCaseDetails);

        JsonNode relatedFiles = callForEntity(Map.of(
                "testCase", testCase, "files", files), JsonNode.class);

        relatedFiles = new JsonMapper().readTree(relatedFiles.toString()
                .replace("\\", "/")
                .replace("//", "/")
                .replace("cypressTafClone/cypress", "."));

        return AgentResult.of("relatedFiles", relatedFiles.toString());
    }
}
