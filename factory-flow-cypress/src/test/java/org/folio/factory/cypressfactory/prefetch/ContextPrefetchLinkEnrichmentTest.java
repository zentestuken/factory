package org.folio.factory.cypressfactory.prefetch;

import com.sun.net.httpserver.HttpServer;
import org.folio.factory.connectors.UnconfiguredConnectors;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.cypressfactory.worker.SkillWorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
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
 *
 * <p>The full Storybook chain (index.json -> repo readme) needs a real host in
 * {@code STORYBOOK_HOST_ORGS} plus GitHub credentials, so it is covered here only by its pure
 * helpers and its unmapped-host path; the end-to-end chain was verified against the live
 * dev.folio.org deployment instead of contorting the production code to be localhost-addressable.</p>
 */
class ContextPrefetchLinkEnrichmentTest {

    private static final String JIRA_BASE_URL = "https://folio-org.atlassian.net";

    /**
     * Long enough to clear the plausibility floor - a page has to look like a document, not like
     * the handful of menu labels a client-rendered shell scrapes down to.
     */
    private static final String FILLER = "Search modes support boolean operators. ".repeat(20);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private ContextPrefetch prefetch() {
        CypressFlowProperties props =
                new CypressFlowProperties("", "main", "", null, null, null, null, 3, null);
        return new ContextPrefetch(
                new SkillWorkspaceService(new UnconfiguredConnectors.GitHub(), props),
                new UnconfiguredConnectors.GitHub(), new UnconfiguredConnectors.Jira(),
                JsonMapper.builder().build(), props, JIRA_BASE_URL);
    }

    private record Stub(String path, int status, String contentType, String body) {}

    private HttpServer startServer(String path, int status, String contentType, String body,
                                    AtomicInteger requestCount) throws IOException {
        return startServer(requestCount, new Stub(path, status, contentType, body));
    }

    private HttpServer startServer(AtomicInteger requestCount, Stub... stubs) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        for (Stub stub : stubs) {
            s.createContext(stub.path(), exchange -> {
                requestCount.incrementAndGet();
                byte[] bytes = stub.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", stub.contentType());
                exchange.sendResponseHeaders(stub.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
        }
        s.start();
        this.server = s;
        return s;
    }

    private static JiraIssue issueWithDescription(String description) {
        return new JiraIssue("UIEH-1566", "s", description, "Open", "Story", List.of(), null);
    }

    /** Runs enrichment for one URL cited by the story itself. */
    private Result enrich(String url) {
        return enrich(issueWithDescription("See " + url), List.of());
    }

    private Result enrich(JiraIssue issue, List<JiraIssue> linkedIssues) {
        StringBuilder out = new StringBuilder();
        Set<String> sources = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        prefetch().enrichLinkedPages(issue, linkedIssues, out, sources, notes);
        return new Result(out.toString(), sources, notes);
    }

    private static JiraIssue linkedIssue(String key, String description) {
        return new JiraIssue(key, "spike", description, "Open", "Task", List.of(), null);
    }

    private record Result(String text, Set<String> sources, List<String> notes) {}

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
    void confluencePagesOnTheJiraHostAreNotExcluded() {
        // Confluence shares the Atlassian domain with Jira but is ordinary requirements prose that
        // nothing else in the pipeline fetches - a blanket domain exclusion dropped it before any
        // fetch was attempted. Jira issue links must still be excluded (resolved by key elsewhere).
        List<String> urls = prefetch().findPublicPageUrls(
                "Spec: " + JIRA_BASE_URL + "/wiki/spaces/FOLIJET/pages/2295070772 "
                        + "and issue " + JIRA_BASE_URL + "/browse/MODDATAIMP-1328");

        assertThat(urls).containsExactly(JIRA_BASE_URL + "/wiki/spaces/FOLIJET/pages/2295070772");
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
    // Storybook resolution helpers (pure)
    // -------------------------------------------------------------------------

    @Test
    void componentDirectoryStripsTheStoriesSubdirectory() {
        // A story file lives in <component>/stories/, but the readme sits beside the component.
        assertThat(ContextPrefetch.componentDirectory("./lib/Select/stories/Select.stories.js"))
                .isEqualTo("lib/Select");
    }

    @Test
    void componentDirectoryKeepsADirectoryThatIsAlreadyTheComponentRoot() {
        assertThat(ContextPrefetch.componentDirectory("./lib/Select/Select.readme.mdx"))
                .isEqualTo("lib/Select");
    }

    @Test
    void preferredDocsImportPathPicksTheReadmeSiblingOverTheStoryFile() {
        JsonNode entries = JsonMapper.builder().build().readTree("""
                {
                  "components-select--basic-usage": {
                    "title": "Components/Select",
                    "importPath": "./lib/Select/stories/Select.stories.js"
                  },
                  "components-select--readme": {
                    "title": "Components/Select",
                    "importPath": "./lib/Select/Select.readme.mdx"
                  },
                  "components-other--readme": {
                    "title": "Components/Other",
                    "importPath": "./lib/Other/Other.readme.mdx"
                  }
                }
                """);

        String path = ContextPrefetch.preferredDocsImportPath(
                entries, entries.path("components-select--basic-usage"));

        assertThat(path).isEqualTo("./lib/Select/Select.readme.mdx");
    }

    @Test
    void preferredDocsImportPathFallsBackToTheStoryFileWhenNoReadmeSiblingExists() {
        JsonNode entries = JsonMapper.builder().build().readTree("""
                {
                  "components-select--basic-usage": {
                    "title": "Components/Select",
                    "importPath": "./lib/Select/stories/Select.stories.js"
                  }
                }
                """);

        String path = ContextPrefetch.preferredDocsImportPath(
                entries, entries.path("components-select--basic-usage"));

        assertThat(path).isEqualTo("./lib/Select/stories/Select.stories.js");
    }

    @Test
    void notesWhenAStorybookHostHasNoRepoMapping() {
        Result result = enrich("http://localhost:1/sb/?path=/story/components-select--basic-usage");

        assertThat(result.text()).isEmpty();
        assertThat(result.notes()).singleElement().asString()
                .contains("no repo mapping is configured for Storybook host")
                .contains("NOT in the generation context");
    }

    // -------------------------------------------------------------------------
    // enrichLinkedPages - HTML scraping path
    // -------------------------------------------------------------------------

    @Test
    void fetchesAndExtractsReadableTextFromAnHtmlPage() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/docs", 200, "text/html; charset=utf-8",
                "<html><head><script>evil()</script><style>.x{}</style></head>"
                        + "<body><nav>Nav</nav><header>Header</header>"
                        + "<p>" + FILLER + "</p>"
                        + "<footer>Footer</footer></body></html>",
                requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/docs";

        Result result = enrich(url);

        assertThat(result.text())
                .contains("### Linked Pages (public)")
                .contains(url)
                .contains("via HTML extraction")
                .contains("Search modes support boolean operators.")
                .doesNotContain("evil()")
                .doesNotContain("Nav")
                .doesNotContain("Header")
                .doesNotContain("Footer");
        assertThat(result.sources()).contains("public page(s)");
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void skipsNonTextPlainContentAndSaysSoAtTheGate() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/image", 200, "image/png", "not text", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/image";

        Result result = enrich(url);

        assertThat(result.text()).isEmpty();
        assertThat(result.sources()).isEmpty();
        assertThat(result.notes()).singleElement().asString()
                .contains("not a document")
                .contains("image/png");
    }

    @Test
    void skipsNon200ResponsesAndSaysSoAtTheGate() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/forbidden", 403, "text/html", "<html>nope</html>", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/forbidden";

        Result result = enrich(url);

        assertThat(result.text()).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("HTTP 403");
    }

    @Test
    void skipsUnreachableHostsWithoutThrowingAndSaysSoAtTheGate() {
        // Port 1 is reserved/unassigned - connection refused, deterministic and fast to fail.
        Result result = enrich("http://localhost:1/nope");

        assertThat(result.text()).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("unreachable");
    }

    @Test
    void truncatesLongPageText() throws IOException {
        String longText = "word ".repeat(3000); // well over the 8_000 char cap
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/long", 200, "text/html",
                "<html><body><p>" + longText + "</p></body></html>", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/long";

        Result result = enrich(url);

        // The model is told the cut was mechanical and how much of the page exists, so it does not
        // read the gap as "nothing more of note".
        assertThat(result.text())
                .contains("TRUNCATED BY THE FACTORY")
                .contains("first 8000 of ")
                .contains("NOT because it was judged irrelevant");
        assertThat(result.notes()).singleElement().asString().contains(url).contains("truncated");
    }

    @Test
    void aShortPageAddsNoTruncationNote() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/short", 200, "text/html",
                "<html><body><p>" + FILLER + "</p></body></html>", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/short";

        Result result = enrich(url);

        assertThat(result.notes()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Plausibility floor - a shell is not content
    // -------------------------------------------------------------------------

    @Test
    void rejectsAPageThatScrapesToOnlyAHandfulOfLabels() throws IOException {
        // What a client-rendered app really yields: a few menu labels, not nothing. Measured at
        // 398 chars for a Google Drive folder, which a blank-only check happily accepted.
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/spa", 200, "text/html",
                "<html><body><div>Name Date modified File size Sort options</div></body></html>",
                requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/spa";

        Result result = enrich(url);

        assertThat(result.text()).isEmpty();
        assertThat(result.sources()).isEmpty();
        assertThat(result.notes()).singleElement().asString()
                .contains("characters of boilerplate came back, not page content")
                .contains("rendered client-side");
    }

    @Test
    void rejectsATelemetryJsonBodyEvenWhenItIsLongEnough() throws IOException {
        // Confluence's own HTML scrapes to {"serverDuration":…} - long enough in principle, but
        // structurally not prose. Padded past the length floor so the JSON check is what rejects it.
        String telemetry = "{\"serverDuration\": 34, \"requestCorrelationId\": \""
                + "c8dff1d36941404d9e2503cf3e852076".repeat(20) + "\"}";
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/telemetry", 200, "text/html",
                "<html><body>" + telemetry + "</body></html>", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/telemetry";

        Result result = enrich(url);

        assertThat(telemetry.length()).isGreaterThan(500); // guards the intent of this test
        assertThat(result.text()).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("not page content");
    }

    // -------------------------------------------------------------------------
    // Markdown variant (.md), as advertised by llms.txt-style docs sites
    // -------------------------------------------------------------------------

    @Test
    void prefersTheMarkdownVariantWhenItIsServedAsMarkdown() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer(requests,
                new Stub("/docs/page.md", 200, "text/markdown; charset=utf-8",
                        "# Using Search Types\n\n* Any\n* Contains\n* Exact Match\n\n" + FILLER),
                new Stub("/docs/page", 200, "text/html",
                        "<html><body><p>flattened html version " + FILLER + "</p></body></html>"));
        String url = "http://localhost:" + s.getAddress().getPort() + "/docs/page";

        Result result = enrich(url);

        assertThat(result.text())
                .contains("via markdown variant (.md)")
                .contains("* Exact Match")            // list structure survives, unlike HTML->text
                .doesNotContain("flattened html version");
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void ignoresAMarkdownVariantThatIsActuallyTheAppsHtmlShell() throws IOException {
        // A single-page app answers 200 with its index.html for any path, so a bare status check
        // would accept a nav skeleton as documentation. Verified against the real Storybook host.
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer(requests,
                new Stub("/docs/page.md", 200, "text/html", "<html><body>SPA shell</body></html>"),
                new Stub("/docs/page", 200, "text/html",
                        "<html><body><p>real html content " + FILLER + "</p></body></html>"));
        String url = "http://localhost:" + s.getAddress().getPort() + "/docs/page";

        Result result = enrich(url);

        assertThat(result.text())
                .contains("via HTML extraction")
                .contains("real html content")
                .doesNotContain("SPA shell");
    }

    @Test
    void anUnreachableHostIsNotRetriedOnceForMarkdownAndAgainForHtml() throws IOException {
        // Each attempt costs a full FETCH_TIMEOUT, so probing .md and then the page itself would
        // double the worst-case wait for every dead link in a ticket.
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/unused", 200, "text/html", "<html/>", requests);
        int freePort = s.getAddress().getPort();
        s.stop(0); // nothing is listening on freePort now: connection refused, deterministically

        Result result = enrich("http://localhost:" + freePort + "/docs");

        assertThat(requests.get()).isZero();
        assertThat(result.notes()).singleElement().asString().contains("unreachable");
    }

    @Test
    void doesNotProbeForAMarkdownVariantWhenTheUrlCarriesAQuery() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/docs", 200, "text/html",
                "<html><body><p>" + FILLER + "</p></body></html>", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/docs?v=2";

        Result result = enrich(url);

        assertThat(result.text()).contains("via HTML extraction");
        assertThat(requests.get()).isEqualTo(1); // no speculative ".md" round trip
    }

    // -------------------------------------------------------------------------
    // Confluence REST resolution
    // -------------------------------------------------------------------------

    @Test
    void readsAConfluencePageThroughItsRestApiInsteadOfScrapingIt() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        String json = """
                {"title": "Use a qualifier and Only compare a part of the value",
                 "body": {"storage": {"value": "<p>Is the selection of Use a qualifier required?</p>\
                <ac:structured-macro ac:name=\\"info\\"><p>No.</p></ac:structured-macro>"}}}
                """;
        HttpServer s = startServer("/wiki/api/v2/pages/2295070772", 200, "application/json",
                json, requests);
        String url = "http://localhost:" + s.getAddress().getPort()
                + "/wiki/spaces/FOLIJET/pages/2295070772";

        Result result = enrich(url);

        assertThat(result.text())
                .contains("via Confluence REST API")
                .contains("Use a qualifier and Only compare a part of the value")
                .contains("Is the selection of Use a qualifier required?")
                .contains("No.")
                .doesNotContain("<p>")
                .doesNotContain("ac:structured-macro");
        assertThat(result.sources()).contains("public page(s)");
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void notesWhenTheConfluenceApiRefusesThePage() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/wiki/api/v2/pages/999", 403, "application/json",
                "{\"message\":\"no\"}", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/wiki/spaces/PRIV/pages/999";

        Result result = enrich(url);

        assertThat(result.text()).isEmpty();
        assertThat(result.notes()).singleElement().asString()
                .contains("Confluence REST API returned HTTP 403")
                .contains("private space");
    }

    @Test
    void notesWhenAConfluencePageHasAnEmptyBody() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/wiki/api/v2/pages/42", 200, "application/json",
                "{\"title\":\"Empty\",\"body\":{\"storage\":{\"value\":\"\"}}}", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/wiki/spaces/X/pages/42";

        Result result = enrich(url);

        assertThat(result.text()).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("empty body");
    }

    // -------------------------------------------------------------------------
    // Per-run link budget
    // -------------------------------------------------------------------------

    private Result enrichAll(List<String> urls) {
        return enrich(issueWithDescription("Links: " + String.join(" ", urls)), List.of());
    }

    // -------------------------------------------------------------------------
    // Links cited by linked issues, and story-first ordering
    // -------------------------------------------------------------------------

    @Test
    void readsALinkCitedOnlyByALinkedIssue() throws IOException {
        // The UIDATIMP-1781 case: the page describing the exact feature under test was cited in a
        // linked spike's description, never in the story, so it was never fetched.
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/spec", 200, "text/html",
                "<html><body><p>the dev-team answers " + FILLER + "</p></body></html>", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/spec";

        Result result = enrich(issueWithDescription("No links in the story itself."),
                List.of(linkedIssue("MODDATAIMP-1328", "See " + url + " for the Q&A.")));

        assertThat(result.text())
                .contains("the dev-team answers")
                .contains("(cited in MODDATAIMP-1328)"); // provenance is visible to the model
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void theStorysOwnLinksAreTriedBeforeALinkedIssuesLinks() throws IOException {
        // Origin outranks resolvability: with a 3-page budget, a linked ticket's links must not
        // displace the story's own, even when the linked one would resolve more cheaply.
        AtomicInteger requests = new AtomicInteger();
        String page = "<html><body><p>" + FILLER + "</p></body></html>";
        HttpServer s = startServer(requests,
                new Stub("/story-a", 200, "text/html", page),
                new Stub("/story-b", 200, "text/html", page),
                new Stub("/story-c", 200, "text/html", page),
                new Stub("/wiki/api/v2/pages/7", 200, "application/json",
                        "{\"title\":\"Spec\",\"body\":{\"storage\":{\"value\":\"<p>from the spike</p>\"}}}"));
        int port = s.getAddress().getPort();
        JiraIssue story = issueWithDescription("Docs: "
                + "http://localhost:" + port + "/story-a?n=1 "
                + "http://localhost:" + port + "/story-b?n=2 "
                + "http://localhost:" + port + "/story-c?n=3");

        Result result = enrich(story, List.of(linkedIssue("MODDATAIMP-1328",
                "Q&A at http://localhost:" + port + "/wiki/spaces/FOLIJET/pages/7")));

        // All three story links fill the budget; the linked issue's Confluence page is reported,
        // not silently dropped - and it is NOT fetched, since the budget filled first.
        assertThat(result.text()).doesNotContain("from the spike");
        assertThat(result.notes()).singleElement().asString()
                .contains("cited in MODDATAIMP-1328")
                .contains("already filled this run's context budget");
    }

    @Test
    void aLinkedIssueLinkIsDedupedAgainstTheStorysOwn() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/docs", 200, "text/html",
                "<html><body><p>" + FILLER + "</p></body></html>", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/docs?n=1";

        Result result = enrich(issueWithDescription("See " + url),
                List.of(linkedIssue("ABC-1", "Also see " + url)));

        assertThat(requests.get()).isEqualTo(1);           // fetched once, not twice
        assertThat(result.text()).doesNotContain("cited in"); // attributed to the story, not the link
    }

    @Test
    void aLinkedIssueWithNoDescriptionIsSkippedWithoutThrowing() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/docs", 200, "text/html",
                "<html><body><p>" + FILLER + "</p></body></html>", requests);
        String url = "http://localhost:" + s.getAddress().getPort() + "/docs";

        Result result = enrich(issueWithDescription("See " + url),
                List.of(linkedIssue("ABC-1", null), linkedIssue("ABC-2", "")));

        assertThat(result.text()).contains("Search modes support boolean operators.");
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void knownResolverLinksStillGoFirstWithinTheLinkedIssueGroup() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer(requests,
                new Stub("/plain", 200, "text/html",
                        "<html><body><p>plain page " + FILLER + "</p></body></html>"),
                new Stub("/wiki/api/v2/pages/9", 200, "application/json",
                        "{\"title\":\"Spec\",\"body\":{\"storage\":{\"value\":\"<p>resolver page</p>\"}}}"));
        int port = s.getAddress().getPort();
        // Plain URL cited first, Confluence second - resolvability must reorder them.
        JiraIssue linked = linkedIssue("ABC-1",
                "http://localhost:" + port + "/plain?n=1 and "
                        + "http://localhost:" + port + "/wiki/spaces/X/pages/9");

        Result result = enrich(issueWithDescription("No story links."), List.of(linked));

        assertThat(result.text().indexOf("resolver page"))
                .isLessThan(result.text().indexOf("plain page"));
    }

    @Test
    void capsTheNumberOfPagesThatLandInThePrompt() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/p", 200, "text/html",
                "<html><body><p>" + FILLER + "</p></body></html>", requests);
        int port = s.getAddress().getPort();
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            urls.add("http://localhost:" + port + "/p?n=" + i);
        }

        Result result = enrichAll(urls);

        assertThat(requests.get()).isEqualTo(3); // MAX_LINK_PAGES worth of readable pages
        // Reported, not silently dropped - but as one note for all of them, not one note each.
        assertThat(result.notes()).singleElement().asString()
                .contains("2 linked pages were not read")
                .contains("already filled this run's context budget");
    }

    @Test
    void unreadableLinksDoNotStarveAReadableOneFurtherDownTheDescription() throws IOException {
        // The failure mode that matters: a ticket citing a Drive folder, a Slack thread and a
        // screenshot before the actual spec. Junk costs a round trip but no prompt bytes, so it
        // must not consume the page budget.
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer(requests,
                new Stub("/shell", 200, "text/html", "<html><body><div>tiny</div></body></html>"),
                new Stub("/spec", 200, "text/html",
                        "<html><body><p>the real requirements " + FILLER + "</p></body></html>"));
        int port = s.getAddress().getPort();
        List<String> urls = List.of(
                "http://localhost:" + port + "/shell?n=1",
                "http://localhost:" + port + "/shell?n=2",
                "http://localhost:" + port + "/shell?n=3",
                "http://localhost:" + port + "/spec?n=4");

        Result result = enrichAll(urls);

        assertThat(result.text()).contains("the real requirements");
        assertThat(requests.get()).isEqualTo(4);
        assertThat(result.notes()).hasSize(3).allSatisfy(note ->
                assertThat(note).contains("not page content"));
    }

    @Test
    void capsTotalAttemptsSoAPathologicalDescriptionCannotFetchForever() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/p", 200, "text/html",
                "<html><body><div>tiny</div></body></html>", requests);
        int port = s.getAddress().getPort();
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            urls.add("http://localhost:" + port + "/p?n=" + i);
        }

        Result result = enrichAll(urls);

        assertThat(requests.get()).isEqualTo(8); // MAX_LINK_ATTEMPTS
        assertThat(result.text()).isEmpty();
        assertThat(result.notes()).hasSize(9);   // 8 rejected as shells, + 1 note for the 2 skipped
        assertThat(result.notes().get(8))
                .contains("2 linked pages were not read")
                .contains("already tried 8 links");
    }

    @Test
    void triesLinksWithAKnownResolverBeforeOnesThatMustBeScraped() throws IOException {
        // The Confluence link is last in the description, and there are already enough readable
        // pages ahead of it to fill the budget - so only resolvability ordering gets it read.
        AtomicInteger requests = new AtomicInteger();
        String page = "<html><body><p>" + FILLER + "</p></body></html>";
        HttpServer s = startServer(requests,
                new Stub("/a", 200, "text/html", page),
                new Stub("/b", 200, "text/html", page),
                new Stub("/c", 200, "text/html", page),
                new Stub("/wiki/api/v2/pages/7", 200, "application/json",
                        "{\"title\":\"The spec\",\"body\":{\"storage\":{\"value\":"
                                + "\"<p>requirements live here</p>\"}}}"));
        int port = s.getAddress().getPort();
        List<String> urls = List.of(
                "http://localhost:" + port + "/a?n=1",
                "http://localhost:" + port + "/b?n=2",
                "http://localhost:" + port + "/c?n=3",
                "http://localhost:" + port + "/wiki/spaces/FOLIJET/pages/7");

        Result result = enrichAll(urls);

        assertThat(result.text())
                .contains("via Confluence REST API")
                .contains("requirements live here");
        // The budget is full, so the last scrape-only link is the one reported instead.
        assertThat(result.notes()).singleElement().asString()
                .contains("/c?n=3")
                .contains("already filled this run's context budget");
    }

    // -------------------------------------------------------------------------
    // Jira smart-link render hint, and the legacy Confluence URL form
    // -------------------------------------------------------------------------

    @Test
    void ignoresTheSmartLinkRenderHintJiraAppendsAsAThirdSegment() {
        // Jira emits `[alias|url|smart-link]` for an inline card. Taking the last pipe segment
        // yielded the literal string "smart-link" as the URL to fetch.
        List<String> urls = prefetch().findPublicPageUrls(
                "See https://folio.org/x|https://developer.ebsco.com/docs/real-page|smart-link here.");

        assertThat(urls).containsExactly("https://developer.ebsco.com/docs/real-page");
    }

    @Test
    void keepsTheOnlyUrlSegmentWhenTheTrailingSegmentsAreNotUrls() {
        List<String> urls = prefetch().findPublicPageUrls("See https://folio.org/page|alias|smart-link.");

        assertThat(urls).containsExactly("https://folio.org/page");
    }

    @Test
    void resolvesTheLegacyConfluenceViewpageUrlThroughTheRestApi() throws IOException {
        // The pre-Cloud form names the same page id, so it must take the REST path rather than
        // falling through to an HTML scrape of the Confluence JS shell.
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer(requests, new Stub("/wiki/api/v2/pages/12345",
                200, "application/json",
                "{\"body\":{\"storage\":{\"value\":\"<p>" + FILLER + "</p>\"}},"
                        + "\"title\":\"Match profile spec\"}"));
        String origin = "http://localhost:" + s.getAddress().getPort();

        Result result = enrich(origin + "/wiki/pages/viewpage.action?pageId=12345");

        assertThat(result.text()).contains("Match profile spec").contains("boolean operators");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void theLegacyConfluenceFormIsPrioritisedLikeTheModernOne() throws IOException {
        // Resolver-first ordering: a legacy Confluence link must outrank an unrecognised URL, or a
        // ticket citing junk first spends the page budget before reaching the spec.
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer(requests,
                new Stub("/wiki/api/v2/pages/777", 200, "application/json",
                        "{\"body\":{\"storage\":{\"value\":\"<p>" + FILLER + "</p>\"}},"
                                + "\"title\":\"The spec\"}"),
                new Stub("/misc", 200, "text/html", "<html><body><p>" + FILLER + "</p></body></html>"));
        String origin = "http://localhost:" + s.getAddress().getPort();

        Result result = enrichAll(List.of(origin + "/misc",
                origin + "/wiki/pages/viewpage.action?pageId=777"));

        assertThat(result.text().indexOf("The spec")).isLessThan(result.text().indexOf("/misc"));
    }

    // -------------------------------------------------------------------------
    // Budget notes are grouped
    // -------------------------------------------------------------------------

    @Test
    void linksCutOffByThePageBudgetShareOneNote() throws IOException {
        // One note per skipped link buried the notes that say something specific - a link that
        // failed, a page that was truncated - in a list of near-identical lines.
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer(requests,
                new Stub("/a", 200, "text/html", "<html><body><p>" + FILLER + "</p></body></html>"),
                new Stub("/b", 200, "text/html", "<html><body><p>" + FILLER + "</p></body></html>"),
                new Stub("/c", 200, "text/html", "<html><body><p>" + FILLER + "</p></body></html>"),
                new Stub("/d", 200, "text/html", "<html><body><p>" + FILLER + "</p></body></html>"),
                new Stub("/e", 200, "text/html", "<html><body><p>" + FILLER + "</p></body></html>"));
        String origin = "http://localhost:" + s.getAddress().getPort();

        Result result = enrichAll(List.of(origin + "/a", origin + "/b", origin + "/c",
                origin + "/d", origin + "/e"));

        assertThat(result.notes()).singleElement().asString()
                .contains("2 linked pages were not read")
                .contains("/d")
                .contains("/e");
    }

    // -------------------------------------------------------------------------
    // Confluence tiny links (/wiki/x/<hash>)
    // -------------------------------------------------------------------------

    @Test
    void decodesTheTinyLinkHashFromTheRealTicket() {
        // Checked against the live host on 2026-10-02: this is the link UIDATIMP-1781 cited through
        // MODDATAIMP-1328, and /wiki/api/v2/pages/1303183418 answers 200 with
        // "2025-12-17 Data Import SIG Meeting Notes".
        assertThat(ContextPrefetch.confluenceTinyLinkPageId("OgCtTQ")).isEqualTo(1303183418L);
    }

    @Test
    void readsTheBytesLittleEndianNotBigEndian() {
        // The big-endian reading of the same bytes is 973122893, which the real API 404s. Pinning
        // this because the two differ only in byte order and both look like plausible page ids.
        assertThat(ContextPrefetch.confluenceTinyLinkPageId("OgCtTQ")).isNotEqualTo(973122893L);
        assertThat(ContextPrefetch.confluenceTinyLinkPageId("5wM")).isEqualTo(999L);
        assertThat(ContextPrefetch.confluenceTinyLinkPageId("Kg")).isEqualTo(42L);
    }

    @Test
    void rejectsAHashThatIsNotAPageId() {
        assertThat(ContextPrefetch.confluenceTinyLinkPageId("not base64!")).isNegative();
        assertThat(ContextPrefetch.confluenceTinyLinkPageId("")).isNegative();
        assertThat(ContextPrefetch.confluenceTinyLinkPageId("AAAAAAAAAAAAAAAA")).isNegative();
    }

    @Test
    void resolvesATinyLinkToTheRestApiWithNoExtraRoundTrip() throws IOException {
        // Following the redirect cannot work - the real tinyurl.action endpoint answers 202 with an
        // AWS WAF bot challenge - so the hash is decoded locally and only the REST call goes out.
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer(requests, new Stub("/wiki/api/v2/pages/999", 200,
                "application/json", "{\"body\":{\"storage\":{\"value\":\"<p>" + FILLER + "</p>\"}},"
                        + "\"title\":\"Suppression spec\"}"));
        String origin = "http://localhost:" + s.getAddress().getPort();

        Result result = enrich(origin + "/wiki/x/5wM");

        assertThat(result.text())
                .contains("via Confluence REST API")
                .contains("Suppression spec")
                .contains("boolean operators");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void reportsATinyLinkWhoseHashIsNotAPageId() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer("/unused", 200, "text/html", "<html/>", requests);
        String origin = "http://localhost:" + s.getAddress().getPort();

        Result result = enrich(origin + "/wiki/x/not_a_real_hash_at_all_because_too_long");

        assertThat(result.text()).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("is not a page id");
        assertThat(requests.get()).isZero();
    }

    @Test
    void aTinyLinkIsPrioritisedLikeAnyOtherConfluenceForm() throws IOException {
        // Resolver-first ordering: without this a ticket citing junk ahead of a tiny link spends
        // the page budget before reaching the spec, which is the regression the ordering exists for.
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer(requests,
                new Stub("/wiki/api/v2/pages/42", 200, "application/json",
                        "{\"body\":{\"storage\":{\"value\":\"<p>" + FILLER + "</p>\"}},"
                                + "\"title\":\"The spec\"}"),
                new Stub("/misc", 200, "text/html", "<html><body><p>" + FILLER + "</p></body></html>"));
        String origin = "http://localhost:" + s.getAddress().getPort();

        Result result = enrichAll(List.of(origin + "/misc", origin + "/wiki/x/Kg"));

        assertThat(result.text().indexOf("The spec")).isLessThan(result.text().indexOf("/misc"));
    }
}
