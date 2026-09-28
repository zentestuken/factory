package org.folio.factory.cypressfactory.prefetch;

import com.sun.net.httpserver.HttpServer;
import org.folio.factory.connectors.UnconfiguredConnectors;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.cypressfactory.worker.SkillWorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Item 12 "Case B" - public, no-auth link enrichment. Uses a real local {@link HttpServer}
 * rather than mocking {@code HttpClient} directly: the behaviour under test is entirely about
 * status codes, content types and response bodies, which a local server exercises for real
 * with no new test dependency.
 */
class ContextPrefetchLinkEnrichmentTest {

    private static final String JIRA_BASE_URL = "https://folio-org.atlassian.net";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private ContextPrefetch prefetch() {
        CypressFlowProperties props =
                new CypressFlowProperties("", "main", "", null, null, null, null, 3, null, null);
        return new ContextPrefetch(
                new SkillWorkspaceService(new UnconfiguredConnectors.GitHub(), props),
                new UnconfiguredConnectors.GitHub(), new UnconfiguredConnectors.Jira(),
                JsonMapper.builder().build(), props, JIRA_BASE_URL);
    }

    private HttpServer startServer(String path, int status, String contentType, String body,
                                    AtomicInteger requestCount) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        s.createContext(path, exchange -> {
            requestCount.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        s.start();
        this.server = s;
        return s;
    }

    private static JiraIssue issueWithDescription(String description) {
        return new JiraIssue("UIEH-1566", "s", description, "Open", "Story", List.of(), null);
    }

    // -------------------------------------------------------------------------
    // findPublicPageUrls
    // -------------------------------------------------------------------------

    @Test
    void excludesTheFactorysOwnJiraInstance() {
        List<String> urls = prefetch().findPublicPageUrls(
                "See " + JIRA_BASE_URL + "/browse/UIEH-9999 and also https://developer.ebsco.com/docs.");

        assertThat(urls).containsExactly("https://developer.ebsco.com/docs");
    }

    @Test
    void stripsTrailingPunctuationAndDedupes() {
        List<String> urls = prefetch().findPublicPageUrls(
                "Docs: https://example.com/a). Also see https://example.com/a) again, and "
                        + "https://example.com/b, please.");

        assertThat(urls).containsExactly("https://example.com/a", "https://example.com/b");
    }

    @Test
    void returnsEmptyWhenNoUrlsPresent() {
        assertThat(prefetch().findPublicPageUrls("Plain story text, no links here.")).isEmpty();
    }

    @Test
    void extractsTheUrlFromAJiraWikiLinkWhoseAliasIsTheSameUrl() {
        // Jira renders `[alias|url]`; when the alias itself has no whitespace (here, the URL
        // repeated as its own display text) the \S+ match swallows both halves via the pipe.
        List<String> urls = prefetch().findPublicPageUrls(
                "See https://dev.folio.org/story?path=select|https://dev.folio.org/story?path=select for usage.");

        assertThat(urls).containsExactly("https://dev.folio.org/story?path=select");
    }

    @Test
    void extractsTheUrlFromAJiraWikiLinkWithADifferentAlias() {
        List<String> urls = prefetch().findPublicPageUrls(
                "See https://short.link/x|https://developer.ebsco.com/docs/real-page for details.");

        assertThat(urls).containsExactly("https://developer.ebsco.com/docs/real-page");
    }

    // -------------------------------------------------------------------------
    // enrichLinkedPages - end to end against a local server
    // -------------------------------------------------------------------------

    @Test
    void fetchesAndExtractsReadableTextFromAnHtmlPage() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/docs", 200, "text/html; charset=utf-8",
                "<html><head><script>evil()</script><style>.x{}</style></head>"
                        + "<body><nav>Nav</nav><header>Header</header>"
                        + "<p>Search modes support boolean operators.</p>"
                        + "<footer>Footer</footer></body></html>",
                requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/docs";
        JiraIssue issue = issueWithDescription("Behavior documented at " + url);

        StringBuilder out = new StringBuilder();
        Set<String> sources = new LinkedHashSet<>();
        List<String> truncationNotes = new ArrayList<>();
        prefetch().enrichLinkedPages(issue, out, sources, truncationNotes);

        assertThat(out.toString())
                .contains("### Linked Pages (public)")
                .contains(url)
                .contains("Search modes support boolean operators.")
                .doesNotContain("evil()")
                .doesNotContain("Nav")
                .doesNotContain("Header")
                .doesNotContain("Footer");
        assertThat(sources).contains("public page(s)");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void skipsNonTextPlainContent() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/image", 200, "image/png", "not text", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/image";
        JiraIssue issue = issueWithDescription("See " + url);

        StringBuilder out = new StringBuilder();
        Set<String> sources = new LinkedHashSet<>();
        List<String> truncationNotes = new ArrayList<>();
        prefetch().enrichLinkedPages(issue, out, sources, truncationNotes);

        assertThat(out.toString()).isEmpty();
        assertThat(sources).isEmpty();
    }

    @Test
    void skipsNon200Responses() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/forbidden", 403, "text/html", "<html>nope</html>", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/forbidden";
        JiraIssue issue = issueWithDescription("See " + url);

        StringBuilder out = new StringBuilder();
        Set<String> sources = new LinkedHashSet<>();
        List<String> truncationNotes = new ArrayList<>();
        prefetch().enrichLinkedPages(issue, out, sources, truncationNotes);

        assertThat(out.toString()).isEmpty();
        assertThat(sources).isEmpty();
    }

    @Test
    void skipsUnreachableHostsWithoutThrowing() {
        // Port 1 is reserved/unassigned - connection refused, deterministic and fast to fail.
        JiraIssue issue = issueWithDescription("See http://localhost:1/nope");

        StringBuilder out = new StringBuilder();
        Set<String> sources = new LinkedHashSet<>();
        List<String> truncationNotes = new ArrayList<>();
        prefetch().enrichLinkedPages(issue, out, sources, truncationNotes);

        assertThat(out.toString()).isEmpty();
        assertThat(sources).isEmpty();
    }

    @Test
    void truncatesLongPageText() throws IOException {
        String longText = "word ".repeat(3000); // well over the 8_000 char cap
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/long", 200, "text/html",
                "<html><body><p>" + longText + "</p></body></html>", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/long";
        JiraIssue issue = issueWithDescription("See " + url);

        StringBuilder out = new StringBuilder();
        Set<String> sources = new LinkedHashSet<>();
        List<String> truncationNotes = new ArrayList<>();
        prefetch().enrichLinkedPages(issue, out, sources, truncationNotes);

        assertThat(out.toString()).contains("… (truncated)");
        assertThat(truncationNotes).singleElement().asString().contains(url);
    }

    @Test
    void aShortPageAddsNoTruncationNote() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/short", 200, "text/html",
                "<html><body><p>short content</p></body></html>", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/short";
        JiraIssue issue = issueWithDescription("See " + url);

        StringBuilder out = new StringBuilder();
        Set<String> sources = new LinkedHashSet<>();
        List<String> truncationNotes = new ArrayList<>();
        prefetch().enrichLinkedPages(issue, out, sources, truncationNotes);

        assertThat(truncationNotes).isEmpty();
    }

    @Test
    void capsTheNumberOfLinksFetchedPerRun() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/p", 200, "text/html", "<html><body>content here</body></html>",
                requests);
        int port = s.getAddress().getPort();
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            urls.add("http://localhost:" + port + "/p?n=" + i);
        }
        JiraIssue issue = issueWithDescription("Links: " + String.join(" ", urls));

        StringBuilder out = new StringBuilder();
        Set<String> sources = new LinkedHashSet<>();
        List<String> truncationNotes = new ArrayList<>();
        prefetch().enrichLinkedPages(issue, out, sources, truncationNotes);

        assertThat(requests.get()).isEqualTo(3); // MAX_PUBLIC_LINKS
    }
}
