package org.folio.factory.autotests.worker;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.autotests.workspace.AutomationWorkspace;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class CypressTestFrameworkExplorerAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-test-framework-explorer";
    private static final JsonMapper JSON = new JsonMapper();
    /** Test data and tooling the explorer never picks; they were most of the old prompt's size. */
    private static final Set<String> SKIPPED_TOP_DIRS = Set.of("fixtures", "jsonSchemas", "plugins", "tasks");

    private final AutomationWorkspace workspace;

    public CypressTestFrameworkExplorerAgentWorker(ChatClient chatClient, AutomationWorkspace workspace) {
        super(chatClient);
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        JsonNode testCase = JSON.readTree(context.requireInput("testCaseDetails").content());
        return AgentResult.of("relatedFiles", explore(testCase, List.of()).toString());
    }

    /**
     * Picks similar tests and support files by path alone.
     *
     * @param areas the case's areas (e.g. {@code eureka}, {@code inventory-browse}); when one
     *              matches {@code e2e/} folders, only those folders' tests are listed. Empty
     *              means every test is listed.
     */
    public JsonNode explore(JsonNode testCase, List<String> areas) {
        String files = String.join("\n", listFiles(workspace.root().resolve("cypress"), areas));
        return callForEntity(Map.of("testCase", testCase, "files", files), JsonNode.class);
    }

    /** Paths relative to {@code cypress/}, as {@code ./e2e/...} - the form the prompt asks for. */
    static List<String> listFiles(Path cypressDir, List<String> areas) {
        List<String> all;
        try (var stream = Files.walk(cypressDir)) {
            all = stream.filter(Files::isRegularFile)
                    .map(p -> cypressDir.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.contains("node_modules/"))
                    .filter(p -> !SKIPPED_TOP_DIRS.contains(p.substring(0, Math.max(0, p.indexOf('/')))))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + cypressDir, e);
        }
        Set<String> e2eDirs = matchingE2eDirs(all, areas);
        return all.stream()
                .filter(p -> e2eDirs.isEmpty() || !p.startsWith("e2e/") || e2eDirs.contains(e2eDir(p)))
                .map(p -> "./" + p)
                .toList();
    }

    /** {@code e2e/} folders sharing a first word with an area: eureka→eureka, inn-reach→inn-reach-integration. */
    static Set<String> matchingE2eDirs(List<String> paths, List<String> areas) {
        Set<String> areaWords = areas.stream().map(CypressTestFrameworkExplorerAgentWorker::firstWord)
                .filter(w -> !w.isBlank()).collect(Collectors.toSet());
        return paths.stream().filter(p -> p.startsWith("e2e/") && p.indexOf('/', 4) > 0)
                .map(CypressTestFrameworkExplorerAgentWorker::e2eDir)
                .filter(dir -> areaWords.contains(firstWord(dir)))
                .collect(Collectors.toSet());
    }

    private static String e2eDir(String path) {
        int end = path.indexOf('/', 4);
        return end < 0 ? "" : path.substring(4, end);
    }

    private static String firstWord(String name) {
        return name.toLowerCase(Locale.ROOT).split("[-_ &]+")[0];
    }
}
