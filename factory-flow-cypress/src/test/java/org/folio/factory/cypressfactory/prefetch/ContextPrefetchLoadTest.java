package org.folio.factory.cypressfactory.prefetch;

import com.sun.net.httpserver.HttpServer;
import org.folio.factory.connectors.UnconfiguredConnectors;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.cypressfactory.worker.SkillWorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Item 12 "Case B" must run regardless of context-file quality - a link the story itself cites
 * is not a compensating measure for a thin area file, so it must not be gated the same way
 * GitHub/Jira enrichment is. These tests exercise {@link ContextPrefetch#load} end to end with
 * a context file complete enough to skip the thin-context enrichment entirely, and confirm the
 * linked-page fetch still happens.
 */
class ContextPrefetchLoadTest {

    private static final String AREA = "inventory";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    /** Passes every SKILL.md step-1b check - real GitHub/Jira enrichment must not fire for this. */
    private static final String COMPLETE_CONTEXT = completeContext();

    private static String completeContext() {
        StringBuilder sb = new StringBuilder("## Key Business Rules for Test Cases\n\n");
        for (int i = 1; i <= 25; i++) sb.append(i).append(". Rule number ").append(i).append('\n');
        sb.append("\n## Exact UI Texts\n\n")
          .append("- toast \"Saved successfully\"\n")
          .append("- toast \"Record deleted successfully\"\n")
          .append("- toast \"Updated successfully\"\n")
          .append("\n## Known Gaps\n\n- one open question\n");
        return sb.toString();
    }

    /** Serves {@code COMPLETE_CONTEXT} for the area context path, nothing for anything else. */
    private static class FakeGitHub implements GitHubConnector {
        @Override
        public void createBranch(String repo, String baseBranch, String newBranch) {
        }

        @Override
        public void commitFiles(String repo, String branch, Map<String, String> files, String message) {
        }

        @Override
        public String createPullRequest(String repo, String headBranch, String baseBranch, String title,
                                        String body) {
            return "";
        }

        @Override
        public String getFileContent(String repo, String branch, String path) {
            return path.endsWith("references/context/" + AREA + ".md") ? COMPLETE_CONTEXT : "";
        }

        @Override
        public List<String> listDirectory(String repo, String branch, String path) {
            return List.of();
        }
    }

    private ContextPrefetch prefetch() {
        CypressFlowProperties props = new CypressFlowProperties(
                "", "main", "fake-org/fake-repo", null, null, null, null, 3, null, null, true);
        return new ContextPrefetch(
                new SkillWorkspaceService(new FakeGitHub(), props),
                new FakeGitHub(), new UnconfiguredConnectors.Jira(),
                JsonMapper.builder().build(), props, "");
    }

    private HttpServer startServer(String body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        s.createContext("/docs", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        s.start();
        this.server = s;
        return s;
    }

    @Test
    void completeContextStillFetchesALinkedPage() throws IOException {
        // Padded past the plausibility floor: a page that scrapes to a couple of sentences is
        // treated as a client-rendered shell, not content (see ContextPrefetchLinkEnrichmentTest).
        HttpServer s = startServer("<html><body><p>"
                + "Search modes support boolean operators. ".repeat(20) + "</p></body></html>");
        String url = "http://localhost:" + s.getAddress().getPort() + "/docs";
        JiraIssue issue = new JiraIssue("UIEH-1566", "s",
                "As per the table at " + url + " - apply these rules.", "Open", "Story", List.of(), null);

        ContextPrefetch.ContextBundle bundle = prefetch().load(List.of(AREA), issue, List.of());

        assertThat(bundle.enriched()).isFalse(); // the context-file-quality signal must stay honest
        assertThat(bundle.text())
                .contains("Search modes support boolean operators.")
                .contains("### Linked Pages (public)");
        assertThat(bundle.qualityNote()).contains("Linked page(s) found in the story text");
    }

    @Test
    void completeContextWithNoLinksAddsNothing() {
        JiraIssue issue = new JiraIssue("UIEH-1567", "s", "Plain story, no links here.",
                "Open", "Story", List.of(), null);

        ContextPrefetch.ContextBundle bundle = prefetch().load(List.of(AREA), issue, List.of());

        assertThat(bundle.text()).doesNotContain("### Linked Pages (public)");
        assertThat(bundle.linkNotes()).isEmpty();
    }

    @Test
    void aTruncatedLinkedPageAddsAFactoryNote() throws IOException {
        HttpServer s = startServer("<html><body><p>" + "word ".repeat(3000) + "</p></body></html>");
        String url = "http://localhost:" + s.getAddress().getPort() + "/docs";
        JiraIssue issue = new JiraIssue("UIEH-1568", "s", "See " + url + " for the full rules.",
                "Open", "Story", List.of(), null);

        ContextPrefetch.ContextBundle bundle = prefetch().load(List.of(AREA), issue, List.of());

        assertThat(bundle.linkNotes()).singleElement().asString().contains(url);
    }
}
