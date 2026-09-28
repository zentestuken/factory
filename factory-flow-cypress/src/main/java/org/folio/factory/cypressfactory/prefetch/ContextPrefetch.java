package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.cypressfactory.worker.SkillWorkspaceService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads area context file(s) and, when SKILL.md step 1b fires, lightweight enrichment.
 *
 * <p>Demoted from the former {@code cypress-context-loader} worker: the thin-context
 * triggers and enrichment logic are unchanged, but the context file now comes from the
 * local skill clone (via {@link SkillWorkspaceService}) rather than the GitHub API, and
 * the input is the raw Jira issue rather than a compressed scope manifest.</p>
 *
 * <p><b>One exception to the thin-context gate:</b> {@link #enrichLinkedPages} (item 12,
 * public-page links embedded in the story text) always runs, regardless of whether the area's
 * context file is complete. A link the ticket itself cites is part of that specific story, not
 * something a maintained area file could ever pre-absorb - so its relevance doesn't depend on
 * how good that unrelated file happens to be. Jira cross-reference links get the same
 * unconditional treatment, in {@code TestCaseGenAgentWorker.mergeUrlReferencedIssues}.</p>
 */
public class ContextPrefetch {

    private static final Logger log = LoggerFactory.getLogger(ContextPrefetch.class);

    private static final String BRANCH = "master";
    private static final int THIN_CONTEXT_KBR_THRESHOLD = 10;
    private static final String KBR_MARKER = "## Key Business Rule";
    private static final Pattern NUMBERED_RULE = Pattern.compile("^\\s*\\d+\\.\\s+\\S");
    private static final int MAX_CONTENT_BYTES = 150 * 1024;
    private static final int MAX_TRANSLATION_ENTRIES = 50;

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    /** Bounds worst-case added latency (public pages, unlike Jira/GitHub, can be arbitrarily slow). */
    private static final int MAX_PUBLIC_LINKS = 3;
    private static final int MAX_PAGE_TEXT_CHARS = 8_000;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(8);

    /** Maps area name → GitHub UI module repo. Only areas with a corresponding ui-* repo. */
    private static final Map<String, String> AREA_GITHUB_REPOS = Map.ofEntries(
            Map.entry("orders", "folio-org/ui-orders"),
            Map.entry("invoices", "folio-org/ui-invoice"),
            Map.entry("finance", "folio-org/ui-finance"),
            Map.entry("receiving", "folio-org/ui-receiving"),
            Map.entry("organizations", "folio-org/ui-organizations"),
            Map.entry("inventory", "folio-org/ui-inventory"),
            Map.entry("requests", "folio-org/ui-requests"),
            Map.entry("mediated-requests", "folio-org/ui-requests-mediated"),
            Map.entry("users", "folio-org/ui-users"),
            Map.entry("fees-fines", "folio-org/ui-users"),
            Map.entry("loans", "folio-org/ui-users"),
            Map.entry("check-in", "folio-org/ui-checkin"),
            Map.entry("check-out", "folio-org/ui-checkout"),
            Map.entry("circulation-settings", "folio-org/ui-circulation"),
            Map.entry("patron-notices", "folio-org/ui-circulation"),
            Map.entry("circulation-log", "folio-org/ui-circulation-log"),
            Map.entry("data-import", "folio-org/ui-data-import"),
            Map.entry("data-export", "folio-org/ui-data-export"),
            Map.entry("marc-authority", "folio-org/ui-marc-authorities"),
            Map.entry("marc-bib-quickmarc", "folio-org/ui-quick-marc"),
            Map.entry("eholdings", "folio-org/ui-eholdings"),
            Map.entry("agreements", "folio-org/ui-agreements"),
            Map.entry("licenses", "folio-org/ui-licenses"),
            Map.entry("lists", "folio-org/ui-lists"),
            Map.entry("bulk-edit", "folio-org/ui-bulk-edit"),
            Map.entry("oai-pmh", "folio-org/ui-oai-pmh"),
            Map.entry("course-reserves", "folio-org/ui-courses"),
            Map.entry("consortium-manager", "folio-org/ui-consortia-settings")
    );

    private static final Set<String> ENRICHMENT_STOPWORDS = Set.of(
            "with", "that", "from", "when", "this", "have", "been", "will", "should",
            "user", "able", "into", "over", "then", "than", "also", "such", "each",
            "does", "used", "both", "while", "case", "test", "page", "story",
            "must", "allow", "and", "for", "the", "not", "can"
    );

    /**
     * @param text          combined context file content plus permissions/enrichment blocks
     * @param sourceNote    one line naming the tier each context file came from
     * @param missingAreas  areas with no context file at all (SKILL.md's sanctioned soft path)
     * @param enriched      whether step 1b enrichment ran
     */
    /**
     * @param qualityNote      what the assessment found, whether enrichment ran and what answered
     *                         it — restated in {@code # Analysis} so the gate reviewer can see how
     *                         much of the domain knowledge came from a maintained file versus a
     *                         live scrape.
     * @param truncationNotes  one line per linked page that was too large to include in full —
     *                         fed into the same {@code ## Factory notes} channel as the test-group
     *                         and coverage-matrix gaps, so a reviewer sees it at the gate instead of
     *                         it sitting silently inside the (already truncated) enrichment text.
     */
    public record ContextBundle(String text, String sourceNote, List<String> missingAreas,
                                boolean enriched, String qualityNote, List<String> truncationNotes) {}

    private record Enrichment(String text, List<String> sources) {}

    private final SkillWorkspaceService skills;
    private final GitHubConnector gitHub;
    private final JiraConnector jira;
    private final JsonMapper jsonMapper;
    private final CypressFlowProperties properties;
    private final String jiraBaseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(FETCH_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ContextPrefetch(SkillWorkspaceService skills, GitHubConnector gitHub, JiraConnector jira,
                           JsonMapper jsonMapper, CypressFlowProperties properties, String jiraBaseUrl) {
        this.skills = skills;
        this.gitHub = gitHub;
        this.jira = jira;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.jiraBaseUrl = jiraBaseUrl == null ? "" : jiraBaseUrl.strip();
    }

    public ContextBundle load(List<String> areas, JiraIssue issue) {
        StringBuilder combined = new StringBuilder();
        List<String> notes = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String area : areas) {
            skills.read("references/context/" + area + ".md")
                    .ifPresentOrElse(asset -> {
                        combined.append(asset.content()).append("\n\n");
                        notes.add(area + ".md (" + asset.detail() + ")");
                    }, () -> {
                        missing.add(area);
                        notes.add(area + ".md (not found)");
                        combined.append(missingContextNote(area)).append("\n\n");
                    });
        }

        String text = combined.toString().strip();

        String permissions = buildPermissionsBlock(areas);
        boolean havePermissions = permissions != null;
        if (havePermissions) {
            text = text + "\n\n" + permissions;
        }

        List<String> shortfalls = assess(text);
        StringBuilder quality = new StringBuilder();
        boolean enriched = false;

        if (shortfalls.isEmpty()) {
            log.info("[context] Areas {} are complete on every check - skipping live enrichment", areas);
            quality.append("complete on every check (")
                   .append(countKeyBusinessRules(text)).append(" key business rules); "
                           + "no enrichment needed");
        } else {
            log.info("[context] Areas {} incomplete ({}) - running live enrichment", areas, shortfalls);
            Enrichment enrichment = buildEnrichment(areas, extractKeywords(issue), issue);
            text = text + "\n\n" + enrichment.text();
            enriched = true;
            quality.append("incomplete \u2014 ").append(String.join("; ", shortfalls))
                   .append(". Live enrichment ran, ")
                   .append(enrichment.sources().isEmpty()
                           ? "but returned no additional data"
                           : "adding " + String.join(", ", enrichment.sources()));
        }

        // Always fetched, independently of the assessment, so it is reported separately -
        // otherwise a run shows "enrichment ran, adding translations" and reads as though the
        // permissions were skipped.
        quality.append(havePermissions
                ? ". package.json permissions loaded"
                : ". No package.json permissions (no GitHub repo mapped for this area, or the "
                        + "fetch failed)");

        // Item 12: links embedded in the story text are part of the story itself, not a
        // compensating measure for a weak context file - a link the ticket cites doesn't become
        // less relevant because an unrelated area file happens to be well maintained. Runs
        // unconditionally, unlike the assessment-gated enrichment above.
        List<String> truncationNotes = new ArrayList<>();
        StringBuilder linkSection = new StringBuilder();
        Set<String> linkSources = new LinkedHashSet<>();
        enrichLinkedPages(issue, linkSection, linkSources, truncationNotes);
        if (!linkSources.isEmpty()) {
            text = text + "\n\n" + linkSection;
            quality.append(". Linked page(s) found in the story text: ")
                    .append(String.join(", ", linkSources));
        }

        return new ContextBundle(text, String.join(" · ", notes), List.copyOf(missing),
                enriched, quality.toString(), List.copyOf(truncationNotes));
    }

    /** SKILL.md's own wording for a missing context file — the soft, sanctioned path. */
    private static String missingContextNote(String area) {
        return "# Context: " + area + "\n\n"
                + "> No context file exists for this area. Generate cases based on the User Story "
                + "alone — results may be less accurate for domain-specific preconditions. "
                + "State this limitation in `# Analysis`.\n";
    }

    // -------------------------------------------------------------------------
    // Context quality assessment (SKILL.md step 1b)
    //
    // Enrichment fires when ANY check fails, so it is skipped only for a file that is complete
    // on every dimension. That bias is deliberate: enrichment is cheap, and more grounding data
    // is worth more than the tokens it costs.
    // -------------------------------------------------------------------------

    boolean isThinContext(String content) {
        return !assess(content).isEmpty();
    }

    /**
     * The five SKILL.md step-1b checks, reported rather than collapsed to a boolean. Any
     * shortfall triggers enrichment, so an empty list means the file is complete on every axis.
     */
    List<String> assess(String content) {
        List<String> shortfalls = new ArrayList<>();
        if (isDraftContext(content)) shortfalls.add("marked DRAFT");
        int rules = countKeyBusinessRules(content);
        if (rules < THIN_CONTEXT_KBR_THRESHOLD) {
            shortfalls.add(rules + " key business rules (want " + THIN_CONTEXT_KBR_THRESHOLD + ")");
        }
        if (!content.contains("## Exact UI Texts")) {
            shortfalls.add("no `## Exact UI Texts` section");
        } else {
            int toasts = countToastsInUiTexts(content);
            if (toasts < 3) shortfalls.add(toasts + " confirmed toasts (want 3)");
        }
        int gaps = countKnownGaps(content);
        if (gaps > 5) shortfalls.add(gaps + " known gaps (want 5 or fewer)");
        return shortfalls;
    }

    private boolean isDraftContext(String content) {
        if (!content.startsWith("---")) return false;
        int end = content.indexOf("---", 3);
        return end > 0 && content.substring(3, end).toUpperCase(Locale.ROOT).contains("DRAFT");
    }

    /**
     * Counts the rules, not the heading. Every context file carries exactly one
     * {@code ## Key Business Rules...} heading with the rules as a numbered list beneath it, so
     * counting heading occurrences returned 1 for every file — below the threshold, which made
     * the whole assessment a constant {@code true} and enrichment unconditional.
     */
    private int countKeyBusinessRules(String content) {
        int start = content.indexOf(KBR_MARKER);
        if (start < 0) return 0;
        int end = content.indexOf("\n## ", start + KBR_MARKER.length());
        String section = end > 0 ? content.substring(start, end) : content.substring(start);
        return (int) section.lines().filter(l -> NUMBERED_RULE.matcher(l).find()).count();
    }

    private int countToastsInUiTexts(String content) {
        int sectionStart = content.indexOf("## Exact UI Texts");
        if (sectionStart < 0) return 0;
        int sectionEnd = content.indexOf("\n## ", sectionStart + 1);
        String section = sectionEnd > 0
                ? content.substring(sectionStart, sectionEnd)
                : content.substring(sectionStart);
        return (int) section.lines()
                .filter(l -> l.toLowerCase(Locale.ROOT).contains("toast")
                        || l.toLowerCase(Locale.ROOT).contains("successfully"))
                .count();
    }

    private int countKnownGaps(String content) {
        int gapsIdx = content.indexOf("Known Gaps");
        if (gapsIdx < 0) return 0;
        int sectionStart = content.lastIndexOf("\n## ", gapsIdx);
        if (sectionStart < 0) return 0;
        int sectionEnd = content.indexOf("\n## ", sectionStart + 4);
        String section = sectionEnd > 0
                ? content.substring(sectionStart, sectionEnd)
                : content.substring(sectionStart);
        return (int) section.lines()
                .filter(l -> l.trim().startsWith("- ") || l.matches("^\\d+\\..*"))
                .count();
    }

    // -------------------------------------------------------------------------
    // Enrichment (SKILL.md "Lightweight Context Enrichment")
    // -------------------------------------------------------------------------

    private List<String> extractKeywords(JiraIssue issue) {
        if (issue == null) return List.of();
        StringBuilder sb = new StringBuilder();
        if (issue.summary() != null) sb.append(' ').append(issue.summary());
        if (issue.description() != null) sb.append(' ').append(issue.description());
        return Arrays.stream(sb.toString().split("[\\s\\p{Punct}]+"))
                .map(w -> w.toLowerCase(Locale.ROOT))
                .filter(w -> w.length() >= 4)
                .filter(w -> !ENRICHMENT_STOPWORDS.contains(w))
                .distinct()
                .limit(15)
                .toList();
    }

    private Enrichment buildEnrichment(List<String> areas, List<String> keywords, JiraIssue issue) {
        StringBuilder content = new StringBuilder();
        Set<String> sources = new LinkedHashSet<>();

        for (String area : areas) {
            enrichGitHub(area, keywords, content, sources);
        }
        enrichJira(issue, content, sources);

        StringBuilder sb = new StringBuilder("## Live Enrichment\n\n");
        if (sources.isEmpty()) {
            sb.append("> ⚠️ Context enrichment attempted but returned no additional data. "
                    + "Cases are based on the existing context file only "
                    + "— verify domain-specific details on first execution.\n\n");
        } else {
            sb.append("> ⚠️ Context was enriched from ").append(String.join(", ", sources))
                    .append(" for this session. "
                            + "Run `build-app-context` to make the enrichment permanent.\n\n");
        }
        sb.append(content);
        return new Enrichment(sb.toString(), List.copyOf(sources));
    }

    private String buildPermissionsBlock(List<String> areas) {
        StringBuilder result = new StringBuilder();
        for (String area : areas) {
            String repo = AREA_GITHUB_REPOS.get(area);
            if (repo == null) continue;
            String permissions = fetchPermissions(repo);
            if (permissions != null) result.append(permissions);
        }
        return result.isEmpty() ? null : result.toString();
    }

    private void enrichGitHub(String area, List<String> keywords, StringBuilder out, Set<String> sources) {
        String repo = AREA_GITHUB_REPOS.get(area);
        if (repo == null) return;
        String moduleName = repo.substring(repo.lastIndexOf('/') + 1);

        String translations = fetchTranslations(repo, moduleName, keywords);
        if (translations != null) {
            out.append(translations);
            sources.add("GitHub (translations)");
        }
    }

    private String fetchPermissions(String repo) {
        String content = safeGetFileContent(repo, BRANCH, "package.json");
        if (content == null) return null;
        try {
            JsonNode pkg = jsonMapper.readTree(content);
            JsonNode permSets = pkg.path("stripes").path("permissionSets");
            if (permSets.isMissingNode() || !permSets.isArray()) return null;
            StringBuilder sb = new StringBuilder("### Permissions (")
                    .append(repo).append(" · package.json)\n\n");
            boolean any = false;
            for (JsonNode ps : permSets) {
                String name = ps.path("permissionName").asString("");
                String display = ps.path("displayName").asString("");
                if (!name.isBlank()) {
                    sb.append("- `").append(name).append('`');
                    if (!display.isBlank()) sb.append(" — \"").append(display).append('"');
                    sb.append('\n');
                    any = true;
                }
            }
            return any ? sb.append('\n').toString() : null;
        } catch (Exception e) {
            log.warn("[context] Failed to parse package.json from {}: {}", repo, e.getMessage());
            return null;
        }
    }

    private String fetchTranslations(String repo, String moduleName, List<String> keywords) {
        if (keywords.isEmpty()) return null;
        String path = "translations/" + moduleName + "/en_US.json";
        String content = safeGetFileContent(repo, BRANCH, path);
        if (content == null) return null;
        try {
            JsonNode node = jsonMapper.readTree(content);
            List<String[]> matched = new ArrayList<>();
            for (String key : node.propertyNames()) {
                String keyLower = key.toLowerCase(Locale.ROOT);
                for (String kw : keywords) {
                    if (keyLower.contains(kw)) {
                        matched.add(new String[]{key, node.get(key).asString("")});
                        break;
                    }
                }
                if (matched.size() >= MAX_TRANSLATION_ENTRIES) break;
            }
            if (matched.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("### UI Labels (")
                    .append(repo).append(" · translations matching: ")
                    .append(String.join(", ", keywords)).append(")\n\n");
            for (String[] entry : matched) {
                sb.append("- `").append(entry[0]).append("`: \"").append(entry[1]).append("\"\n");
            }
            return sb.append('\n').toString();
        } catch (Exception e) {
            log.warn("[context] Failed to parse translations from {}/{}: {}", repo, path, e.getMessage());
            return null;
        }
    }

    private void enrichJira(JiraIssue issue, StringBuilder out, Set<String> sources) {
        if (issue == null || issue.key() == null || issue.key().isBlank()) return;
        try {
            List<JiraIssue> linked = jira.getLinkedIssues(issue.key());
            if (linked.isEmpty()) return;
            out.append("### Linked Issues (Jira)\n\n");
            for (JiraIssue li : linked) {
                out.append("**").append(li.key()).append("** (")
                        .append(li.issueType()).append("): ").append(li.summary()).append('\n');
                if (li.description() != null && !li.description().isBlank()) {
                    String desc = li.description();
                    if (desc.length() > 500) desc = desc.substring(0, 500) + "…";
                    out.append("> ").append(desc.replace("\n", "\n> ")).append('\n');
                }
                out.append('\n');
            }
            sources.add("Jira (linked issues)");
        } catch (Exception e) {
            log.warn("[context] Failed to fetch linked issues for {}: {}", issue.key(), e.getMessage());
        }
    }

    /**
     * Fetches readable text from public, no-auth URLs found in the story text - the item 12
     * "Case B" enrichment. Jira cross-reference URLs are excluded here: those are handled
     * separately, by-key, through the authenticated Jira connector
     * ({@code TestCaseGenAgentWorker.mergeUrlReferencedIssues}), not by scraping a web page.
     *
     * <p>No pre-classification of which links are "worth" fetching: the unauthenticated GET
     * attempt itself is the classification. A 401/403, a non-2xx status, a non-text content
     * type, or any network failure all collapse to the same outcome - skip this one link and
     * move on. Never fails the step; this is best-effort grounding, not a required input.</p>
     *
     * <p>A page that gets truncated still contributes its capped excerpt - partial grounding
     * beats none - but also adds a line to {@code truncationNotes}, so a reviewer at the gate
     * sees "this may be incomplete" instead of only silently getting less than the full page. No
     * attempt is made to guess whether the truncated part mattered; that judgment is left to a
     * human, deliberately, rather than an automatic (and unverifiable) LLM summarization pass.</p>
     */
    void enrichLinkedPages(JiraIssue issue, StringBuilder out, Set<String> sources,
                           List<String> truncationNotes) {
        if (issue == null || issue.description() == null || issue.description().isBlank()) {
            return;
        }
        List<String> urls = findPublicPageUrls(issue.description());
        if (urls.isEmpty()) {
            return;
        }

        StringBuilder section = new StringBuilder();
        int fetched = 0;
        for (String url : urls) {
            if (fetched >= MAX_PUBLIC_LINKS) break;
            FetchedPage page = fetchReadableText(url);
            if (page == null) continue;
            section.append("### ").append(url).append('\n').append(page.text()).append("\n\n");
            if (page.truncated()) {
                truncationNotes.add("Linked page " + url + " is large and was truncated in the "
                        + "generation context - verify nothing scenario-relevant was cut before approving.");
            }
            fetched++;
        }
        if (fetched > 0) {
            out.append("### Linked Pages (public)\n\n").append(section);
            sources.add("public page(s)");
        }
    }

    /** URLs in the story text, minus anything pointing at this factory's own Jira instance. */
    List<String> findPublicPageUrls(String text) {
        List<String> urls = new ArrayList<>();
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String url = extractFromWikiLinkToken(stripTrailingPunctuation(m.group()));
            if (!jiraBaseUrl.isBlank() && url.startsWith(jiraBaseUrl)) continue;
            if (!urls.contains(url)) urls.add(url);
        }
        return urls;
    }

    private static String stripTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ")].,;:!?\"'".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    /**
     * Jira wiki markup renders a link as {@code [alias|url]}. {@code \S+} only starts matching
     * at "http(s)://", so this only bites when the alias itself is a run of non-whitespace text
     * (often the URL repeated as its own display text, e.g. {@code [https://x|https://x]}) -
     * then the match swallows both halves joined by the pipe. The URL is always the last
     * {@code |}-separated segment; pipe isn't a valid unencoded URI character, so splitting on
     * the last one is safe even if the alias itself happens to be a second URL.
     */
    private static String extractFromWikiLinkToken(String token) {
        int lastPipe = token.lastIndexOf('|');
        return lastPipe < 0 ? token : token.substring(lastPipe + 1);
    }

    private record FetchedPage(String text, boolean truncated) {}

    private FetchedPage fetchReadableText(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(FETCH_TIMEOUT)
                    .header("User-Agent", "folio-ai-sdlc-factory/1.0 (+cypress-flow context enrichment)")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.info("[context] Public link {} returned HTTP {} - skipping", url, response.statusCode());
                return null;
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!contentType.contains("text/html") && !contentType.contains("text/plain")) {
                log.info("[context] Public link {} has content-type '{}' - skipping", url, contentType);
                return null;
            }
            String text = contentType.contains("text/html")
                    ? extractReadableHtml(response.body())
                    : response.body();
            if (text == null || text.isBlank()) {
                log.info("[context] Public link {} fetched but had no readable text after stripping "
                        + "script/nav/etc. (likely a JS-rendered page) - skipping", url);
                return null;
            }
            boolean truncated = text.length() > MAX_PAGE_TEXT_CHARS;
            String result = truncated ? text.substring(0, MAX_PAGE_TEXT_CHARS) + "… (truncated)" : text;
            return new FetchedPage(result, truncated);
        } catch (Exception e) {
            log.info("[context] Could not fetch public link {}: {}", url, e.getMessage());
            return null;
        }
    }

    private static final String BOILERPLATE_SELECTOR =
            "script, style, nav, header, footer, noscript, aside, form, iframe, svg, "
            + "[role=navigation], [role=banner], [role=contentinfo], [role=complementary], "
            + ".sidebar, .breadcrumb, .breadcrumbs, .cookie, .cookie-banner, .advertisement, "
            + ".ad, .toc, .table-of-contents, .site-header, .site-footer, .menu, .pagination, "
            + ".comments, .social-share, .related-posts";

    private static final String MAIN_CONTENT_SELECTOR =
            "article, main, [role=main], .content, #content, .main-content, .article-body, "
            + ".post-content, .documentation";

    /** Strips common boilerplate regions, then prefers a semantic main-content container when
     * the page has one. Deliberately a basic heuristic, not a full "readability" algorithm - a
     * Readability-port library isn't worth it here: the Java ones drag in a Kotlin runtime and
     * an older jsoup transitively, against this module's own "no transitive bloat" jsoup pin
     * (see this module's pom.xml) for a page that mostly just needs less boilerplate mixed in. */
    private static String extractReadableHtml(String html) {
        Document doc = Jsoup.parse(html);
        doc.select(BOILERPLATE_SELECTOR).remove();
        Element main = doc.selectFirst(MAIN_CONTENT_SELECTOR);
        if (main != null && main.text().length() > 200) {
            return main.text();
        }
        return doc.body() != null ? doc.body().text() : doc.text();
    }

    private String safeGetFileContent(String repo, String branch, String path) {
        try {
            String content = gitHub.getFileContent(repo, branch, path);
            if (content == null || content.isBlank()) return null;
            if (content.length() > MAX_CONTENT_BYTES) {
                log.warn("[context] File {}/{}/{} exceeds 150 KB - skipping", repo, branch, path);
                return null;
            }
            return content;
        } catch (Exception e) {
            log.warn("[context] Failed to fetch {}/{}/{}: {}", repo, branch, path, e.getMessage());
            return null;
        }
    }
}
