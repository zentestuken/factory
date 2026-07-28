package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans the TAF (stripes-testing) for Cypress fragment files and permissions that match
 * the story's feature area and keywords.
 *
 * When TafSyncService has a ready local clone it searches the filesystem directly,
 * scoring candidates by both filename and content keyword matches. When the clone
 * is not yet ready it falls back to the GitHub contents API.
 *
 * Output: fragment_library.md with matched fragment source files and a permissions
 * list extracted from cypress/support/dictionary/permissions.js.
 */
public class FragmentScannerWorker implements AgentWorker {

    public static final String ID = "cypress-fragment-scanner";

    private static final Logger log = LoggerFactory.getLogger(FragmentScannerWorker.class);

    private static final String TAF_REPO = "folio-org/stripes-testing";
    private static final String TAF_BRANCH = "master";
    private static final String FRAGMENTS_BASE = "cypress/support/fragments";
    private static final String PERMISSIONS_PATH = "cypress/support/dictionary/permissions.js";

    private static final int MAX_FETCHED_FILES = 2;

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "a", "an", "the", "is", "in", "for", "of", "to", "with", "and", "or",
            "on", "at", "by", "as", "be", "are", "was", "it", "its", "from", "that",
            "this", "which", "add", "new", "edit", "update", "display", "show", "create",
            "delete", "remove", "save", "view", "use", "set", "get", "has", "have"));

    /**
     * Maps factory area names to actual stripes-testing fragment directory names.
     * Only areas where the names differ are listed; all others use the area name directly.
     */
    private static final Map<String, String> AREA_TO_DIR = Map.ofEntries(
            Map.entry("check-in", "checkin"),
            Map.entry("check-out", "checkout"),
            Map.entry("circulation-settings", "circulation"),
            Map.entry("course-reserves", "courses"),
            Map.entry("data-import", "data_import"),
            Map.entry("fees-fines", "users"),
            Map.entry("marc-authority", "marcAuthority"),
            Map.entry("marc-bib-quickmarc", "inventory"),
            Map.entry("mediated-requests", "requests"),
            Map.entry("patron-notices", "circulation")
    );

    /**
     * Maps factory area names to gui value prefixes found in permissions.js.
     * The permissions.js file is flat — permissions are matched by gui value prefix.
     */
    private static final Map<String, List<String>> AREA_GUI_PREFIXES = Map.ofEntries(
            Map.entry("agreements", List.of("Agreements:")),
            Map.entry("bulk-edit", List.of("Bulk Edit:", "Bulk edit:", "Settings (Bulk edit):")),
            Map.entry("check-in", List.of("Check in:", "Checkin:")),
            Map.entry("check-out", List.of("Check out:", "Checkout:")),
            Map.entry("circulation-settings", List.of("Circulation:", "Settings (Circ")),
            Map.entry("circulation-log", List.of("Circulation log:")),
            Map.entry("consortium-manager", List.of("Consortium", "Settings (Consortia")),
            Map.entry("course-reserves", List.of("Course Reserves:", "Course reserves:")),
            Map.entry("data-import", List.of("Data import:", "Data Import:")),
            Map.entry("data-export", List.of("Data export:", "Data Export:")),
            Map.entry("eholdings", List.of("eHoldings:")),
            Map.entry("fees-fines", List.of("Fees/Fines:", "Fee/Fine:", "Fees:")),
            Map.entry("finance", List.of("Finance:", "Budget:", "Ledger:", "Fiscal year:", "Fiscal Year:")),
            Map.entry("inventory", List.of("Inventory:", "Call number browse:", "Subject browse:")),
            Map.entry("invoices", List.of("Invoices:", "Invoice:")),
            Map.entry("licenses", List.of("Licenses:", "License:")),
            Map.entry("lists", List.of("Lists:", "List:")),
            Map.entry("loans", List.of("Loans:", "Loan:")),
            Map.entry("marc-authority", List.of("MARC Authority:")),
            Map.entry("marc-bib-quickmarc", List.of("quickMARC:", "MARC Bibliographic:")),
            Map.entry("mediated-requests", List.of("Mediated requests:", "Mediated Requests:")),
            Map.entry("orders", List.of("Orders:", "Purchase Orders:", "Purchase orders:")),
            Map.entry("organizations", List.of("Organizations:", "Organization:")),
            Map.entry("patron-notices", List.of("Patron notices:", "Patron Notices:")),
            Map.entry("receiving", List.of("Receiving:", "Receiving ")),
            Map.entry("requests", List.of("Requests:", "Request:")),
            Map.entry("users", List.of("Users:", "User management:"))
    );

    private static final Pattern GUI_PATTERN =
            Pattern.compile("gui:\\s*['\"]([^'\"]+)['\"]");

    private final TafSyncService tafSync;
    private final GitHubConnector gitHub;
    private final FrontmatterCodec frontmatterCodec;

    public FragmentScannerWorker(TafSyncService tafSync, GitHubConnector gitHub,
                                  FrontmatterCodec frontmatterCodec) {
        this.tafSync = tafSync;
        this.gitHub = gitHub;
        this.frontmatterCodec = frontmatterCodec;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        Frontmatter contextFm = frontmatterCodec.parse(context.requireInput("context.md").content());
        List<String> areas = readAreas(contextFm.metadata());
        if (areas.isEmpty()) {
            log.info("No areas in context.md — skipping fragment scan");
            return emptyResult();
        }

        String summary = frontmatterCodec.parse(context.requireInput("scope_manifest.md").content())
                .metadata().path("summary").asString("");
        List<String> keywords = extractKeywords(summary);

        StringBuilder md = new StringBuilder("# Fragment Library\n\n");

        if (tafSync.isReady()) {
            md.append("TAF: `").append(TAF_REPO).append("` branch `").append(TAF_BRANCH)
              .append("` — local clone at `").append(tafSync.tafRoot()).append("`\n\n");
            for (String area : areas) {
                appendAreaSectionLocal(md, area, keywords);
            }
        } else {
            log.info("TAF clone not ready — using GitHub API fallback");
            if (gitHub instanceof ConnectorHealth ch && !ch.isConfigured()) {
                log.info("GitHub connector also not configured — fragment library will be empty");
                return emptyResult();
            }
            md.append("TAF: `").append(TAF_REPO).append("` branch `").append(TAF_BRANCH)
              .append("` — GitHub API (local clone in progress or unavailable)\n\n");
            for (String area : areas) {
                appendAreaSectionRemote(md, area, keywords);
            }
        }

        return AgentResult.of("fragment_library.md", md.toString());
    }

    // ── Local filesystem path ──────────────────────────────────────────────────

    private void appendAreaSectionLocal(StringBuilder md, String area, List<String> keywords) {
        md.append("## Area: ").append(area).append("\n\n");

        String dir = AREA_TO_DIR.getOrDefault(area, area);
        Path fragmentsDir = tafSync.tafRoot().resolve(FRAGMENTS_BASE).resolve(dir);

        if (Files.isDirectory(fragmentsDir)) {
            appendFragmentsLocal(md, fragmentsDir, keywords);
        } else {
            md.append("_No fragment directory found at `").append(
                    tafSync.tafRoot().relativize(fragmentsDir)).append("`._\n\n");
        }

        appendPermissionsLocal(md, area);
    }

    private void appendFragmentsLocal(StringBuilder md, Path fragmentsDir, List<String> keywords) {
        List<Path> jsFiles;
        try (Stream<Path> stream = Files.list(fragmentsDir)) {
            jsFiles = stream
                    .filter(p -> p.toString().endsWith(".js") && Files.isRegularFile(p))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            md.append("_Could not list fragments: ").append(e.getMessage()).append("_\n\n");
            return;
        }
        if (jsFiles.isEmpty()) {
            md.append("_No .js fragment files found._\n\n");
            return;
        }

        List<ScoredFile> scored = jsFiles.stream()
                .map(p -> new ScoredFile(p, scoreLocal(p, keywords)))
                .sorted(Comparator.comparingInt(ScoredFile::score).reversed())
                .toList();

        List<ScoredFile> toFetch = scored.subList(0, Math.min(MAX_FETCHED_FILES, scored.size()));
        List<ScoredFile> others  = scored.subList(Math.min(MAX_FETCHED_FILES, scored.size()), scored.size());

        for (ScoredFile sf : toFetch) {
            try {
                String content = Files.readString(sf.path());
                String relPath = tafSync.tafRoot().relativize(sf.path()).toString().replace('\\', '/');
                md.append("### ").append(relPath).append("\n\n");
                md.append("```js\n").append(content).append("\n```\n\n");
            } catch (IOException e) {
                log.debug("Could not read {}: {}", sf.path(), e.getMessage());
            }
        }

        if (!others.isEmpty()) {
            md.append("**Other fragments in this area:**\n");
            others.forEach(sf -> md.append("- `").append(sf.path().getFileName()).append("`\n"));
            md.append("\n");
        }
    }

    private int scoreLocal(Path file, List<String> keywords) {
        if (keywords.isEmpty()) return 0;
        String filename = normalizeFilename(file.getFileName().toString());
        String content;
        try {
            content = Files.readString(file).toLowerCase();
        } catch (IOException e) {
            content = "";
        }
        int score = 0;
        for (String kw : keywords) {
            if (filename.contains(kw)) score += 3;
            else if (content.contains(kw)) score += 1;
        }
        return score;
    }

    private void appendPermissionsLocal(StringBuilder md, String area) {
        Path permFile = tafSync.tafRoot().resolve(PERMISSIONS_PATH);
        if (!Files.isRegularFile(permFile)) return;
        try {
            List<String> matched = matchPermissions(Files.readString(permFile), area);
            if (!matched.isEmpty()) {
                md.append("### Permissions\n\n");
                md.append("Use the exact `gui` string in test case preconditions:\n\n");
                matched.forEach(p -> md.append("- `").append(p).append("`\n"));
                md.append("\n");
            }
        } catch (IOException e) {
            log.debug("Could not read permissions.js: {}", e.getMessage());
        }
    }

    // ── Permissions matching ───────────────────────────────────────────────────

    private List<String> matchPermissions(String content, String area) {
        List<String> prefixes = AREA_GUI_PREFIXES.getOrDefault(area, List.of());
        if (prefixes.isEmpty()) return List.of();

        List<String> result = new ArrayList<>();
        Matcher m = GUI_PATTERN.matcher(content);
        while (m.find()) {
            String gui = m.group(1);
            for (String prefix : prefixes) {
                if (gui.startsWith(prefix)) {
                    result.add(gui);
                    break;
                }
            }
        }
        return result;
    }

    // ── GitHub API fallback ────────────────────────────────────────────────────

    private void appendAreaSectionRemote(StringBuilder md, String area, List<String> keywords) {
        String dir = AREA_TO_DIR.getOrDefault(area, area);
        String dirPath = FRAGMENTS_BASE + "/" + dir;
        List<String> files = gitHub.listDirectory(TAF_REPO, TAF_BRANCH, dirPath);
        List<String> jsFiles = files.stream().filter(f -> f.endsWith(".js")).toList();

        md.append("## Area: ").append(area).append("\n\n");

        if (jsFiles.isEmpty()) {
            md.append("_No fragment files found in `").append(dirPath).append("`._\n\n");
            return;
        }

        List<String> ranked = jsFiles.stream()
                .sorted(Comparator.comparingInt((String f) -> scoreRemote(f, keywords)).reversed())
                .toList();
        List<String> toFetch = ranked.subList(0, Math.min(MAX_FETCHED_FILES, ranked.size()));
        List<String> others  = ranked.subList(Math.min(MAX_FETCHED_FILES, ranked.size()), ranked.size());

        for (String filename : toFetch) {
            String content = gitHub.getFileContent(TAF_REPO, TAF_BRANCH, dirPath + "/" + filename);
            if (content != null && !content.isBlank()) {
                md.append("### ").append(dirPath).append("/").append(filename).append("\n\n");
                md.append("```js\n").append(content).append("\n```\n\n");
            }
        }
        if (!others.isEmpty()) {
            md.append("**Other fragments in `").append(dirPath).append("`:**\n");
            others.forEach(f -> md.append("- `").append(f).append("`\n"));
            md.append("\n");
        }

        // permissions not available in API-only mode (permissions.js is large; skip for fallback)
    }

    private int scoreRemote(String filename, List<String> keywords) {
        if (keywords.isEmpty()) return 0;
        String normalized = normalizeFilename(filename);
        int score = 0;
        for (String kw : keywords) {
            if (normalized.contains(kw)) score++;
        }
        return score;
    }

    // ── Shared helpers ─────────────────────────────────────────────────────────

    private List<String> readAreas(JsonNode metadata) {
        JsonNode areasNode = metadata.path("areas");
        List<String> areas = new ArrayList<>();
        if (areasNode.isArray()) {
            for (JsonNode n : areasNode) {
                String v = n.asString("");
                if (!v.isBlank()) areas.add(v);
            }
        }
        return areas;
    }

    private List<String> extractKeywords(String summary) {
        if (summary == null || summary.isBlank()) return List.of();
        return Arrays.stream(summary.toLowerCase().split("[^a-z0-9]+"))
                .filter(w -> w.length() >= 3 && !STOPWORDS.contains(w))
                .distinct()
                .toList();
    }

    private String normalizeFilename(String filename) {
        return filename.replaceAll("\\.js$", "").toLowerCase()
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[_\\-]", " ");
    }

    private AgentResult emptyResult() {
        return AgentResult.of("fragment_library.md",
                "# Fragment Library\n\n_TAF not available and GitHub fallback not configured._\n");
    }

    private record ScoredFile(Path path, int score) {}
}
