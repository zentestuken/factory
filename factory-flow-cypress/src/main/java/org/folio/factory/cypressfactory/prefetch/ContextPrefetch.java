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
import java.util.Base64;
import java.util.Arrays;
import java.util.Comparator;
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
    /**
     * How many linked pages may land in the generation prompt. This is the constraint that
     * actually matters: the enrichment text goes into the <i>user</i> message ({@code
     * context_files}) while prompt caching is system-only, so every accepted page is re-sent at
     * full price on each generation attempt, and it competes with the ticket and the area context
     * file for the model's attention. Bounding wall-clock is not the point - the engine's step
     * lease is half an hour and a real run is dominated by the LLM calls themselves.
     */
    private static final int MAX_LINK_PAGES = 3;
    /**
     * How many URLs may be tried per run, readable or not. Deliberately larger than
     * {@link #MAX_LINK_PAGES}: a link that turns out to be unreadable costs one round trip but no
     * prompt bytes, so counting failures against the page budget would let three junk links
     * (a Drive folder, a Slack thread, a screenshot) starve the one link that carries the spec.
     * This cap exists only to stop a pathological description from fetching forever.
     */
    private static final int MAX_LINK_ATTEMPTS = 8;
    private static final int MAX_PAGE_TEXT_CHARS = 8_000;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(8);

    /**
     * Below this, scraped HTML is treated as a shell rather than content. Measured against real
     * ticket links: a Confluence page scrapes to 82 chars of {@code {"serverDuration":…}} telemetry
     * and a Google Drive folder to 398 chars of sort-menu chrome. Both are non-blank, so a
     * blank-only check passed them through and the model was handed them as if they were the page.
     *
     * <p>Applies to scraped HTML only - never to a resolver result (Confluence REST, a repo
     * readme). Those come from a structured source, where "short" means genuinely short rather
     * than "wrong document".</p>
     */
    private static final int MIN_PLAUSIBLE_PAGE_CHARS = 500;

    /** Confluence page URL on any Atlassian site: {@code /wiki/spaces/<KEY>/pages/<id>[/slug]}. */
    private static final Pattern CONFLUENCE_PAGE =
            Pattern.compile("^(https?://[^/]+)/wiki/spaces/[^/]+/pages/(\\d+)");
    /**
     * The pre-Cloud Confluence URL form, still produced by older pages and by anything that
     * copied a link years ago: {@code /wiki/pages/viewpage.action?pageId=<id>}. It names the same
     * page id the modern form does, so it resolves through the same REST call - without this it
     * fell through to the HTML scrape, which on Confluence returns the JS shell.
     */
    private static final Pattern CONFLUENCE_LEGACY_PAGE = Pattern.compile(
            "^(https?://[^/]+)/wiki/pages/viewpage\\.action\\?pageId=(\\d+)");

    /**
     * A Confluence "tiny link" ({@code /wiki/x/<hash>}), which is what the Share dialog copies by
     * default, so tickets carry them often. Untreated it fell through to an HTML scrape of the
     * Confluence JS shell. Seen on UIDATIMP-1781 (2026-10-02).
     *
     * <p>The hash is not an opaque key needing a lookup: it is the page id itself, base64url of its
     * little-endian bytes with trailing zeros and padding stripped. So this resolves with no extra
     * round trip at all. Following the redirect, which was the obvious approach, does not work -
     * checked against the live host, {@code /wiki/x/OgCtTQ} redirects to
     * {@code /wiki/pages/tinyurl.action?urlIdentifier=OgCtTQ} and that answers HTTP 202 with an
     * empty body and {@code x-amzn-waf-action: challenge}, i.e. a bot challenge no plain HTTP
     * client passes.</p>
     */
    private static final Pattern CONFLUENCE_SHORT_LINK =
            Pattern.compile("^(https?://[^/]+)/wiki/x/([A-Za-z0-9_-]+)");

    /** Storybook deep link: {@code <origin>/<project>/?path=/story|docs/<storyId>}. */
    private static final Pattern STORYBOOK_LINK =
            Pattern.compile("^(https?://[^/]+)/([^/?#]+)/?\\?path=/(?:story|docs)/([^/&#]+)");
    /**
     * Storybook doc hosts whose first path segment is also the GitHub repo name, mapped to the org
     * that owns it. Verified for dev.folio.org: stripes-components, stripes-core and
     * stripes-smart-components all resolve to real {@code folio-org/<segment>} repos.
     */
    private static final Map<String, String> STORYBOOK_HOST_ORGS =
            Map.of("dev.folio.org", "folio-org");
    /** Readme-ish files in a component directory, best first. Matched case-insensitively. */
    private static final Pattern README_FILE =
            Pattern.compile("(?i)^(?:.*\\.)?readme\\.(?:mdx|md)$");

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
     * @param linkNotes        one line per linked page the run could not present in full: truncated
     *                         because it was too large, or not read at all (unreachable, not a
     *                         document, no readable content, no resolver for it). Fed into the same
     *                         {@code ## Factory notes} channel as the test-group and coverage-matrix
     *                         gaps, so the gate reviewer sees "this link went unread" explicitly
     *                         instead of it being silently absent from the enrichment text — a link
     *                         the ticket cites is often load-bearing requirements, and the human at
     *                         the gate is the one who can paste the content in and re-generate.
     */
    public record ContextBundle(String text, String sourceNote, List<String> missingAreas,
                                boolean enriched, String qualityNote, List<String> linkNotes) {}

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

    public ContextBundle load(List<String> areas, JiraIssue issue, List<JiraIssue> linkedIssues) {
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
        List<String> linkNotes = new ArrayList<>();
        StringBuilder linkSection = new StringBuilder();
        Set<String> linkSources = new LinkedHashSet<>();
        enrichLinkedPages(issue, linkedIssues, linkSection, linkSources, linkNotes);
        if (!linkSources.isEmpty()) {
            text = text + "\n\n" + linkSection;
            quality.append(". Linked page(s) found in the story text: ")
                    .append(String.join(", ", linkSources));
        }

        return new ContextBundle(text, String.join(" · ", notes), List.copyOf(missing),
                enriched, quality.toString(), List.copyOf(linkNotes));
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
        // Linked issues used to be rendered here too, at a 500-char-per-description cut, while the
        // worker renders the same issues into `linked_issues` unconditionally at 1000. Every run
        // whose context was thin - which is nearly all of them - therefore carried each linked
        // issue twice, at two different lengths. Removed: the worker's copy is the canonical one,
        // is always present rather than assessment-gated, and is strictly the longer of the two.

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
     * beats none - but also adds a line to {@code linkNotes}, so a reviewer at the gate
     * sees "this may be incomplete" instead of only silently getting less than the full page. No
     * attempt is made to guess whether the truncated part mattered; that judgment is left to a
     * human, deliberately, rather than an automatic (and unverifiable) LLM summarization pass.</p>
     *
     * <p><b>Every link that cannot be presented gets a note too</b>, not just a log line. A link
     * the ticket cites is frequently load-bearing requirements (a Confluence spec, a component's
     * docs), and the gate reviewer is the one who can paste the missing content into the
     * re-generation feedback field - which they can only do if they know it went unread.</p>
     */
    void enrichLinkedPages(JiraIssue issue, List<JiraIssue> linkedIssues, StringBuilder out,
                           Set<String> sources, List<String> linkNotes) {
        List<LinkCandidate> candidates = linkCandidates(issue, linkedIssues);
        if (candidates.isEmpty()) {
            return;
        }

        StringBuilder section = new StringBuilder();
        List<String> overPageBudget = new ArrayList<>();
        List<String> overAttemptBudget = new ArrayList<>();
        int attempted = 0;
        int accepted = 0;
        for (LinkCandidate candidate : candidates) {
            String url = candidate.url();
            String origin = candidate.citedIn() == null
                    ? "" : " (cited in " + candidate.citedIn() + ")";
            if (accepted >= MAX_LINK_PAGES) {
                overPageBudget.add(url + origin);
                continue;
            }
            if (attempted >= MAX_LINK_ATTEMPTS) {
                overAttemptBudget.add(url + origin);
                continue;
            }
            attempted++;
            LinkRead read = readLinkedPage(url);
            if (!read.ok()) {
                log.info("[context] Linked page {} not read: {}", url, read.failure());
                linkNotes.add("Linked page " + url + origin + " could not be read ("
                        + read.failure() + ") - its content is NOT in the generation context. If it "
                        + "carries requirements, paste them into the re-generation feedback at this gate.");
                continue;
            }
            section.append("### ").append(url).append(origin)
                    .append(" (via ").append(read.via()).append(")\n")
                    .append(read.text()).append("\n\n");
            if (read.truncated()) {
                linkNotes.add("Linked page " + url + origin + " is large and was truncated in the "
                        + "generation context - verify nothing scenario-relevant was cut before approving.");
            }
            accepted++;
        }
        if (!overPageBudget.isEmpty()) {
            linkNotes.add(budgetNote(overPageBudget, MAX_LINK_PAGES
                    + " other page(s) had already filled this run's context budget"));
        }
        if (!overAttemptBudget.isEmpty()) {
            linkNotes.add(budgetNote(overAttemptBudget, "this run had already tried "
                    + MAX_LINK_ATTEMPTS + " links without filling its context budget"));
        }
        if (accepted > 0) {
            out.append("### Linked Pages (public)\n\n").append(section);
            sources.add("public page(s)");
        }
    }

    /**
     * One note for all the links a budget cut off, rather than one note each. A ticket that cites a
     * dozen links used to produce a dozen near-identical gate notes, which buries the notes that
     * say something specific (a link that failed, a page that was truncated) in the same list.
     */
    private static String budgetNote(List<String> urls, String reason) {
        return (urls.size() == 1 ? "1 linked page was not read" : urls.size()
                + " linked pages were not read")
                + " because " + reason + ": " + String.join(", ", urls)
                + ". Paste anything scenario-relevant from them into the re-generation feedback.";
    }

    /** A URL to try, plus the linked issue that cited it ({@code null} when the story itself did). */
    private record LinkCandidate(String url, String citedIn) {}

    /**
     * Every URL worth trying, in the order to try them: <b>the story's own links first</b>, then
     * links found in linked issues' descriptions.
     *
     * <p>Origin outranks resolvability here. A link the story itself cites is the story's own
     * evidence; one from a related ticket is weaker, and with a 3-page budget a linked ticket's
     * links must not displace the story's. Within each group the known-resolver URLs still go
     * first, since those are both cheaper and more likely to be prose worth having.</p>
     *
     * <p>Linked issues are scanned because the page that matters is often cited there rather than
     * in the story: on UIDATIMP-1781 the dev-team Q&A Confluence page - the document describing the
     * exact feature under test - appeared only in a linked spike's description, so it was never
     * fetched. This reads the linked issue's <i>full</i> description, not the truncated copy that
     * goes into the prompt, so a URL past that cut is still found.</p>
     */
    private List<LinkCandidate> linkCandidates(JiraIssue issue, List<JiraIssue> linkedIssues) {
        List<LinkCandidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (issue != null && issue.description() != null) {
            for (String url : byResolvability(findPublicPageUrls(issue.description()))) {
                if (seen.add(url)) candidates.add(new LinkCandidate(url, null));
            }
        }
        if (linkedIssues == null) {
            return candidates;
        }
        List<LinkCandidate> fromLinked = new ArrayList<>();
        for (JiraIssue linked : linkedIssues) {
            if (linked == null || linked.description() == null) continue;
            for (String url : findPublicPageUrls(linked.description())) {
                if (seen.add(url)) fromLinked.add(new LinkCandidate(url, linked.key()));
            }
        }
        fromLinked.sort(Comparator.comparingInt(candidate -> resolverRank(candidate.url())));
        candidates.addAll(fromLinked);
        return candidates;
    }

    /**
     * URLs in the story text, minus anything pointing at this factory's own Jira <i>issues</i>.
     *
     * <p>The Jira-host exclusion is deliberately not a blanket domain match: Confluence lives on
     * the same Atlassian site under {@code /wiki/}, and a linked Confluence page is ordinary
     * requirements prose that nothing else in the pipeline fetches — excluding it by domain meant
     * those links were dropped here, before any fetch was even attempted. Jira issue links stay
     * excluded because they are resolved by key through the authenticated connector instead
     * ({@code TestCaseGenAgentWorker.mergeUrlReferencedIssues}).</p>
     */
    List<String> findPublicPageUrls(String text) {
        List<String> urls = new ArrayList<>();
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String url = extractFromWikiLinkToken(stripTrailingPunctuation(m.group()));
            if (isOwnJiraIssueUrl(url)) continue;
            if (!urls.contains(url)) urls.add(url);
        }
        return urls;
    }

    /**
     * Known-resolver URLs first, original order preserved within each group.
     *
     * <p>Description order is arbitrary with respect to value, and the page budget is small: a
     * ticket that cites a Drive folder and two screenshots before the Confluence spec would spend
     * the budget on the first three and never read the spec. A URL a resolver recognises is both
     * cheaper (one API call, no 8-second timeout to lose) and more likely to be prose worth having,
     * so it goes first.</p>
     */
    private static List<String> byResolvability(List<String> urls) {
        return urls.stream().sorted(Comparator.comparingInt(ContextPrefetch::resolverRank)).toList();
    }

    private static int resolverRank(String url) {
        return confluenceRef(url) != null
                || CONFLUENCE_SHORT_LINK.matcher(url).find()
                || STORYBOOK_LINK.matcher(url).find() ? 0 : 1;
    }

    private boolean isOwnJiraIssueUrl(String url) {
        if (jiraBaseUrl.isBlank() || !url.startsWith(jiraBaseUrl)) return false;
        String path = url.substring(jiraBaseUrl.length());
        return !path.startsWith("/wiki/");
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
     * then the match swallows both halves joined by the pipe. Pipe is not a valid unencoded URI
     * character, so splitting on it is safe even when the alias is itself a URL.
     *
     * <p>Taking the <i>last</i> segment is not enough, though: Jira also emits a third segment
     * naming the render style, as in {@code [alias|url|smart-link]}, and that produced the literal
     * string {@code "smart-link"} as the URL to fetch. The last segment that actually looks like a
     * URL is the URL.</p>
     */
    private static String extractFromWikiLinkToken(String token) {
        if (token.indexOf('|') < 0) return token;
        String[] segments = token.split("\\|");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (segments[i].startsWith("http://") || segments[i].startsWith("https://")) {
                return segments[i];
            }
        }
        return token;
    }

    /**
     * One link's outcome. Exactly one of {@code text} / {@code failure} is set; {@code via} names
     * the strategy that produced the text, both for the enrichment heading and so a debug dump
     * shows whether a page was scraped or resolved from a structured source.
     */
    private record LinkRead(String text, boolean truncated, String via, String failure,
                            boolean unreachable) {
        static LinkRead ok(String text, boolean truncated, String via) {
            return new LinkRead(text, truncated, via, null, false);
        }

        static LinkRead failed(String reason) {
            return new LinkRead(null, false, null, reason, false);
        }

        /** No response at all - so a second strategy against the same host is pointless. */
        static LinkRead noResponse() {
            return new LinkRead(null, false, null, "unreachable, or the request timed out", true);
        }

        boolean ok() {
            return failure == null;
        }
    }

    /**
     * Resolves a URL to the best available text for it, cheapest and most authoritative first.
     *
     * <p>The ordering matters more than any single strategy. Scraping rendered HTML is the
     * <i>last</i> resort, not the default, because for a lot of real documentation the page is a
     * worse representation of the content than something already published as text: a Confluence
     * page's REST body, a component's readme in its repo, a docs site's own {@code .md} variant.
     * Those also sidestep client-side rendering entirely rather than fighting it - which is why
     * this needs no headless browser.</p>
     */
    /**
     * The matcher for whichever Confluence URL form this is, already positioned on the match
     * ({@code group(1)} origin, {@code group(2)} page id), or {@code null} when it is not a
     * Confluence page link at all.
     */
    private static Matcher confluenceRef(String url) {
        for (Pattern pattern : List.of(CONFLUENCE_PAGE, CONFLUENCE_LEGACY_PAGE)) {
            Matcher m = pattern.matcher(url);
            if (m.find()) return m;
        }
        return null;
    }

    private LinkRead readLinkedPage(String url) {
        Matcher tiny = CONFLUENCE_SHORT_LINK.matcher(url);
        if (tiny.find()) {
            return readConfluenceShortLink(tiny.group(1), tiny.group(2));
        }
        Matcher confluence = confluenceRef(url);
        if (confluence != null) {
            return readConfluencePage(confluence.group(1), confluence.group(2));
        }
        Matcher storybook = STORYBOOK_LINK.matcher(url);
        if (storybook.find()) {
            return readStorybookStory(storybook.group(1), storybook.group(2), storybook.group(3));
        }
        LinkRead markdown = readMarkdownVariant(url);
        if (markdown.ok()) {
            return markdown;
        }
        // A host that didn't answer the .md probe won't answer the page fetch either, and each
        // attempt costs a full FETCH_TIMEOUT - don't pay it twice for one dead link.
        if (markdown.unreachable()) {
            return markdown;
        }
        return scrapeHtmlPage(url);
    }

    /**
     * Confluence's REST body, which is served as structured XHTML and needs no JS to render. Works
     * unauthenticated on a publicly-readable space (verified against folio-org's FOLIJET space); the
     * page id is already in the URL, so there is nothing to search for. Without this, the same URL
     * scrapes to ~80 characters of {@code {"serverDuration":…}} telemetry.
     */
    private LinkRead readConfluenceShortLink(String origin, String hash) {
        long pageId = confluenceTinyLinkPageId(hash);
        if (pageId <= 0) {
            return LinkRead.failed("Confluence tiny link hash '" + hash + "' is not a page id");
        }
        return readConfluencePage(origin, Long.toString(pageId));
    }

    /**
     * The page id encoded in a tiny link hash, or {@code -1} if it does not decode to one.
     *
     * <p>Verified against the live host on 2026-10-02: {@code OgCtTQ} decodes to 1303183418, which
     * {@code /wiki/api/v2/pages/1303183418} answers with "2025-12-17 Data Import SIG Meeting Notes"
     * and ~20k characters of body. The big-endian reading of the same bytes gives 973122893, which
     * 404s - so the byte order here is not a guess.</p>
     */
    static long confluenceTinyLinkPageId(String hash) {
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(hash);
        } catch (IllegalArgumentException e) {
            return -1;
        }
        if (raw.length == 0 || raw.length > Long.BYTES) {
            return -1;
        }
        long id = 0;
        for (int i = raw.length - 1; i >= 0; i--) {
            id = (id << 8) | (raw[i] & 0xFFL);
        }
        return id > 0 ? id : -1;
    }

    private LinkRead readConfluencePage(String origin, String pageId) {
        String api = origin + "/wiki/api/v2/pages/" + pageId + "?body-format=storage";
        HttpResponse<String> response = fetch(api, "application/json");
        if (response == null) {
            return LinkRead.failed("Confluence REST API did not answer for page " + pageId);
        }
        if (response.statusCode() != 200) {
            return LinkRead.failed("Confluence REST API returned HTTP " + response.statusCode()
                    + " for page " + pageId + " (private space, or the page was removed)");
        }
        try {
            JsonNode body = jsonMapper.readTree(response.body());
            String storage = body.path("body").path("storage").path("value").asString("");
            if (storage.isBlank()) {
                return LinkRead.failed("Confluence page " + pageId + " has an empty body");
            }
            String title = body.path("title").asString("");
            String text = Jsoup.parse(storage).text();
            String full = title.isBlank() ? text : title + "\n\n" + text;
            return cap(full, "Confluence REST API");
        } catch (Exception e) {
            return LinkRead.failed("Confluence REST response could not be parsed: " + e.getMessage());
        }
    }

    /**
     * A Storybook story URL resolved to the component's readme in its own repo.
     *
     * <p>Storybook publishes {@code index.json}, which maps every story id to the source file it
     * was built from - so the mapping is exact rather than guessed from the kebab-cased id. The
     * prose lives in the component's readme rather than the story file, so a {@code --readme} /
     * {@code --docs} sibling in the same title group is preferred when present.</p>
     *
     * <p>The deployed build and the repo's default branch drift apart (the live FOLIO build is
     * v13.1.0 and its {@code Select.readme.mdx} 404s on {@code main}, where the file is now
     * {@code readme.md}), so the <i>directory</i> from {@code importPath} is what gets used, and the
     * readme is found by listing it rather than by trusting the exact filename.</p>
     */
    private LinkRead readStorybookStory(String origin, String project, String storyId) {
        String org = STORYBOOK_HOST_ORGS.get(URI.create(origin).getHost());
        if (org == null) {
            return LinkRead.failed("no repo mapping is configured for Storybook host " + origin);
        }
        HttpResponse<String> index = fetch(origin + "/" + project + "/index.json", "application/json");
        if (index == null || index.statusCode() != 200) {
            return LinkRead.failed("Storybook index.json was not available at " + origin + "/" + project);
        }
        String importPath;
        try {
            JsonNode entries = jsonMapper.readTree(index.body()).path("entries");
            JsonNode entry = entries.path(storyId);
            if (entry.isMissingNode()) {
                return LinkRead.failed("story '" + storyId + "' is not in this Storybook's index");
            }
            importPath = preferredDocsImportPath(entries, entry);
        } catch (Exception e) {
            return LinkRead.failed("Storybook index.json could not be parsed: " + e.getMessage());
        }
        String repo = org + "/" + project;
        String dir = componentDirectory(importPath);
        String readme = findReadme(repo, dir);
        if (readme == null) {
            return LinkRead.failed("no readme found in " + repo + "/" + dir
                    + " for story '" + storyId + "'");
        }
        return cap(readme, "Storybook index -> " + repo + " readme");
    }

    /** The {@code --readme}/{@code --docs} sibling's source file when the title group has one. */
    static String preferredDocsImportPath(JsonNode entries, JsonNode story) {
        String title = story.path("title").asString("");
        for (String candidate : List.of("--readme", "--docs")) {
            for (String id : entries.propertyNames()) {
                if (!id.endsWith(candidate)) continue;
                JsonNode sibling = entries.path(id);
                if (title.equals(sibling.path("title").asString(""))) {
                    return sibling.path("importPath").asString("");
                }
            }
        }
        return story.path("importPath").asString("");
    }

    /**
     * The component's own directory from a Storybook {@code importPath}. Story files usually sit in
     * a {@code stories/} subdirectory ({@code ./lib/Select/stories/Select.stories.js}) while the
     * readme sits beside the component itself, so that one level is stripped.
     */
    static String componentDirectory(String importPath) {
        String path = importPath.replaceFirst("^\\./", "");
        int lastSlash = path.lastIndexOf('/');
        String dir = lastSlash < 0 ? "" : path.substring(0, lastSlash);
        return dir.endsWith("/stories") ? dir.substring(0, dir.length() - "/stories".length()) : dir;
    }

    private String findReadme(String repo, String dir) {
        try {
            for (String name : gitHub.listDirectory(repo, "main", dir)) {
                String file = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                if (README_FILE.matcher(file).matches()) {
                    String content = gitHub.getFileContent(repo, "main", dir + "/" + file);
                    if (content != null && !content.isBlank()) return content;
                }
            }
        } catch (Exception e) {
            log.info("[context] Could not read {}/{} from GitHub: {}", repo, dir, e.getMessage());
        }
        return null;
    }

    /**
     * The {@code <url>.md} variant an increasing number of docs platforms publish for agents
     * (advertised via {@code /llms.txt}). Better than scraping the same page even when scraping
     * works: markdown keeps the list and heading structure that HTML-to-text flattening destroys.
     *
     * <p>Requires a {@code text/markdown} content type rather than trusting the status code - a
     * single-page app typically answers <i>200 with its HTML shell</i> for any path, so a bare
     * status check would silently accept a nav skeleton as documentation.</p>
     */
    private LinkRead readMarkdownVariant(String url) {
        if (url.matches("(?i).*\\.(md|html?|json|txt|pdf)$") || url.contains("?")) {
            return LinkRead.failed("not a candidate for a .md variant");
        }
        HttpResponse<String> response = fetch(url.replaceAll("/$", "") + ".md", "text/markdown");
        if (response == null) {
            return LinkRead.noResponse();
        }
        if (response.statusCode() != 200) {
            return LinkRead.failed("no .md variant");
        }
        if (!response.headers().firstValue("content-type").orElse("").contains("text/markdown")) {
            return LinkRead.failed("the .md variant was not served as markdown");
        }
        String body = response.body();
        return body == null || body.isBlank()
                ? LinkRead.failed("the .md variant was empty")
                : cap(body, "markdown variant (.md)");
    }

    /** Last resort: fetch the page and strip it to text. */
    private LinkRead scrapeHtmlPage(String url) {
        HttpResponse<String> response = fetch(url, "text/html");
        if (response == null) {
            return LinkRead.noResponse();
        }
        if (response.statusCode() != 200) {
            return LinkRead.failed("HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!contentType.contains("text/html") && !contentType.contains("text/plain")) {
            return LinkRead.failed("not a document - content-type '" + contentType + "'");
        }
        String text = contentType.contains("text/html")
                ? extractReadableHtml(response.body())
                : response.body();
        if (text == null || text.isBlank()) {
            return LinkRead.failed("no readable text after stripping script/nav/etc. - "
                    + "almost certainly rendered client-side");
        }
        if (looksLikeShell(text)) {
            return LinkRead.failed("only " + text.length() + " characters of boilerplate came back, "
                    + "not page content - almost certainly rendered client-side");
        }
        return cap(text, "HTML extraction");
    }

    /**
     * Whether an extraction is too thin or too structural to be the page's actual content. A
     * client-rendered page rarely scrapes to nothing: it scrapes to a little telemetry JSON or a
     * few menu labels, which is why a blank-only check is not enough.
     */
    private static boolean looksLikeShell(String text) {
        String trimmed = text.strip();
        return trimmed.length() < MIN_PLAUSIBLE_PAGE_CHARS || trimmed.startsWith("{");
    }

    /**
     * Caps a page at {@link #MAX_PAGE_TEXT_CHARS}, marking the cut with its mechanism and both
     * counts. A bare ellipsis reads as "nothing more of note"; what the model needs to know is that
     * the tail was never fetched, and how much of the page it is actually holding - 8,000 of 8,400
     * characters is nearly all of it, 8,000 of 90,000 is an excerpt.
     */
    private LinkRead cap(String text, String via) {
        if (text.length() <= MAX_PAGE_TEXT_CHARS) {
            return LinkRead.ok(text, false, via);
        }
        return LinkRead.ok(text.substring(0, MAX_PAGE_TEXT_CHARS)
                + "\n\n[TRUNCATED BY THE FACTORY: first " + MAX_PAGE_TEXT_CHARS + " of "
                + text.length() + " characters of this page. The rest was not read - it was cut to a "
                + "length limit, NOT because it was judged irrelevant. Open the link if the missing "
                + "part matters.]", true, via);
    }

    /** One GET, or null if it could not be completed at all. Never throws. */
    private HttpResponse<String> fetch(String url, String accept) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(FETCH_TIMEOUT)
                    .header("User-Agent", "folio-ai-sdlc-factory/1.0 (+cypress-flow context enrichment)")
                    .header("Accept", accept + ", */*")
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.info("[context] GET {} failed: {}", url, e.getMessage());
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
