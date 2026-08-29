package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.cypressfactory.worker.SkillWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads area context file(s) and, when SKILL.md step 1b fires, lightweight enrichment.
 *
 * <p>Demoted from the former {@code cypress-context-loader} worker: the thin-context
 * triggers and enrichment logic are unchanged, but the context file now comes from the
 * local skill clone (via {@link SkillWorkspaceService}) rather than the GitHub API, and
 * the input is the raw Jira issue rather than a compressed scope manifest.</p>
 */
public class ContextPrefetch {

    private static final Logger log = LoggerFactory.getLogger(ContextPrefetch.class);

    private static final String BRANCH = "master";
    private static final int THIN_CONTEXT_KBR_THRESHOLD = 10;
    private static final String KBR_MARKER = "## Key Business Rule";
    private static final int MAX_CONTENT_BYTES = 150 * 1024;
    private static final int MAX_TRANSLATION_ENTRIES = 50;

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
    public record ContextBundle(String text, String sourceNote, List<String> missingAreas, boolean enriched) {}

    private final SkillWorkspaceService skills;
    private final GitHubConnector gitHub;
    private final JiraConnector jira;
    private final JsonMapper jsonMapper;
    private final CypressFlowProperties properties;

    public ContextPrefetch(SkillWorkspaceService skills, GitHubConnector gitHub, JiraConnector jira,
                           JsonMapper jsonMapper, CypressFlowProperties properties) {
        this.skills = skills;
        this.gitHub = gitHub;
        this.jira = jira;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
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
        if (permissions != null) {
            text = text + "\n\n" + permissions;
        }

        boolean enriched = false;
        if (isThinContext(text)) {
            log.info("[context] Areas {} are thin - running lightweight enrichment", areas);
            text = text + "\n\n" + buildEnrichment(areas, extractKeywords(issue), issue);
            enriched = true;
        }

        return new ContextBundle(text, String.join(" · ", notes), List.copyOf(missing), enriched);
    }

    /** SKILL.md's own wording for a missing context file — the soft, sanctioned path. */
    private static String missingContextNote(String area) {
        return "# Context: " + area + "\n\n"
                + "> No context file exists for this area. Generate cases based on the User Story "
                + "alone — results may be less accurate for domain-specific preconditions. "
                + "State this limitation in `# Analysis`.\n";
    }

    // -------------------------------------------------------------------------
    // Thin-context detection (SKILL.md step 1b — enrichment fires when ANY is true)
    // -------------------------------------------------------------------------

    boolean isThinContext(String content) {
        return isDraftContext(content)
                || countKeyBusinessRules(content) < THIN_CONTEXT_KBR_THRESHOLD
                || !content.contains("## Exact UI Texts")
                || countToastsInUiTexts(content) < 3
                || countKnownGaps(content) > 5;
    }

    private boolean isDraftContext(String content) {
        if (!content.startsWith("---")) return false;
        int end = content.indexOf("---", 3);
        return end > 0 && content.substring(3, end).toUpperCase(Locale.ROOT).contains("DRAFT");
    }

    private int countKeyBusinessRules(String content) {
        int count = 0, idx = 0;
        while ((idx = content.indexOf(KBR_MARKER, idx)) != -1) {
            count++;
            idx += KBR_MARKER.length();
        }
        return count;
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

    private String buildEnrichment(List<String> areas, List<String> keywords, JiraIssue issue) {
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
        return sb.toString();
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
