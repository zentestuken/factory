package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContextLoaderWorkerThinContextTest {

    private final FrontmatterCodec codec = new FrontmatterCodec();

    private static String kbrContent(int count) {
        StringBuilder sb = new StringBuilder("# Context\n\n");
        for (int i = 1; i <= count; i++) {
            sb.append("## Key Business Rule ").append(i).append("\n\nSome rule.\n\n");
        }
        return sb.toString();
    }

    private ContextLoaderWorker worker(String contextContent) {
        GitHubConnector github = new GitHubConnector() {
            @Override public void createBranch(String r, String b, String n) {}
            @Override public void commitFiles(String r, String b, java.util.Map<String, String> f, String m) {}
            @Override public String createPullRequest(String r, String h, String b, String t, String body) { return ""; }
            @Override public String getFileContent(String r, String b, String p) { return contextContent; }
            @Override public java.util.List<String> listDirectory(String r, String b, String p) { return java.util.List.of(); }
        };
        CypressFlowProperties props = new CypressFlowProperties("o/r", "main", "ai-repo", null);
        return new ContextLoaderWorker(github, codec, props);
    }

    private AgentContext contextFor(String issueKey) {
        String manifest = codec.render(Map.of("issue_key", issueKey, "summary", "s",
                "components", List.of(), "endpoints", List.of(),
                "risk_level", "low", "ambiguities", List.of(),
                "acceptance_criteria", List.of(), "ecs_enabled", false, "team", ""), "## Analysis\n");
        return new AgentContext(UUID.randomUUID(), "context-load",
                Map.of("scope_manifest.md", new org.folio.factory.core.agent.ArtifactContent(
                        "scope_manifest.md", 1, "text/markdown", manifest)),
                null, Map.of(), List.of("context.md"));
    }

    @Test
    void addsThinFlagWhenFewerThan10Kbrs() {
        AgentResult result = worker(kbrContent(5)).execute(contextFor("UIIN-1"));

        String content = result.outputs().get("context.md");
        assertThat(content).startsWith("---");
        assertThat(content).contains("context_quality:");
    }

    @Test
    void noFlagWhenAtLeast10Kbrs() {
        AgentResult result = worker(kbrContent(10)).execute(contextFor("UIIN-1"));

        String content = result.outputs().get("context.md");
        assertThat(content).startsWith("---");
        assertThat(content).contains("areas:");
        assertThat(content).doesNotContain("context_quality");
        assertThat(content).contains("## Key Business Rule");
    }

    @Test
    void thresholdIsExclusive_9KbrsIsThin() {
        AgentResult result = worker(kbrContent(9)).execute(contextFor("UIIN-1"));

        assertThat(result.outputs().get("context.md")).contains("context_quality:");
    }
}
