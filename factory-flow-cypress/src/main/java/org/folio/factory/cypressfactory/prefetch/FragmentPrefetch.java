package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.cypressfactory.worker.TafSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans the TAF (stripes-testing) for Cypress fragments and permissions matching the
 * story's area and keywords.
 *
 * <p>Demoted from the former {@code cypress-fragment-scanner} worker; scoring and the
 * GitHub fallback are unchanged. Fragments are a soft dependency — when they are
 * unavailable, test case content is unaffected and only automation hints degrade.</p>
 */
public class FragmentPrefetch {

    /**
     * @param sourceNote which tier answered and how much it found - reported in the provenance
     *                   block, because "no warning" is not the same as "the clone was there".
     */
    public record Fragments(String text, String sourceNote) {
        public boolean unavailable() {
            return text.contains("_Unavailable —");
        }
    }


    private static final Logger log = LoggerFactory.getLogger(FragmentPrefetch.class);

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
     * Factory area names → stripes-testing fragment directories or single {@code .js} files (relative
     * to {@code cypress/support/fragments}), where they differ from the area name. An area whose page
     * objects are spread out lists them all; the files are scored and capped as one pool. Eureka has
     * no {@code fragments/eureka} - its specs live under {@code e2e/eureka} and import their page
     * objects from the two settings directories, plus the two {@code users/} files that carry role
     * and capability assignment (the rest of {@code users/} is patron-side and would only crowd them).
     */
    private static final Map<String, List<String>> AREA_TO_DIRS = Map.ofEntries(
            Map.entry("check-in", List.of("checkin")),
            Map.entry("check-out", List.of("checkout")),
            Map.entry("circulation-settings", List.of("circulation")),
            Map.entry("course-reserves", List.of("courses")),
            Map.entry("data-import", List.of("data_import")),
            Map.entry("eureka", List.of("settings/authorization-roles", "settings/authorization-policies",
                    "users/userEdit.js", "users/usersCard.js")),
            Map.entry("fees-fines", List.of("users")),
            Map.entry("marc-authority", List.of("marcAuthority")),
            Map.entry("marc-bib-quickmarc", List.of("inventory")),
            Map.entry("mediated-requests", List.of("requests")),
            Map.entry("patron-notices", List.of("circulation"))
    );

    static List<String> dirsFor(String area) {
        return AREA_TO_DIRS.getOrDefault(area, List.of(area));
    }

    /** Factory area names → gui value prefixes in the flat permissions.js. */
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

    private static final Pattern GUI_PATTERN = Pattern.compile("gui:\\s*['\"]([^'\"]+)['\"]");

    private final TafSyncService tafSync;
    private final GitHubConnector gitHub;

    public FragmentPrefetch(TafSyncService tafSync, GitHubConnector gitHub) {
        this.tafSync = tafSync;
        this.gitHub = gitHub;
    }

    /** Never throws — fragments are a soft dependency. */
    public Fragments scan(List<String> areas, String summary) {
        try {
            return doScan(areas, summary);
        } catch (Exception e) {
            log.warn("[fragments] Scan failed: {} - continuing without a fragment library", e.getMessage());
            return unavailable("scan failed: " + e.getMessage());
        }
    }

    private Fragments doScan(List<String> areas, String summary) {
        if (areas == null || areas.isEmpty()) return unavailable("no area detected");

        List<String> keywords = extractKeywords(summary);
        StringBuilder md = new StringBuilder("# Fragment Library\n\n");

        if (tafSync.isReady()) {
            md.append("TAF: `").append(TAF_REPO).append("` branch `").append(TAF_BRANCH)
              .append("` — local clone at `").append(tafSync.tafRoot()).append("`\n\n");
            for (String area : areas) {
                appendAreaSectionLocal(md, area, keywords);
            }
            return new Fragments(md.toString(), describe("local clone", md));
        } else {
            log.info("[fragments] TAF clone not ready - using GitHub API fallback");
            if (gitHub instanceof ConnectorHealth ch && !ch.isConfigured()) {
                return unavailable("TAF clone not ready and GitHub connector not configured");
            }
            md.append("TAF: `").append(TAF_REPO).append("` branch `").append(TAF_BRANCH)
              .append("` — GitHub API (local clone in progress or unavailable)\n\n");
            for (String area : areas) {
                appendAreaSectionRemote(md, area, keywords);
            }
        }
        return new Fragments(md.toString(), describe("GitHub API", md));
    }

    /** Counts what actually made it into the block, so the note reflects content, not intent. */
    private static String describe(String tier, StringBuilder md) {
        long inlined = md.toString().lines().filter(l -> l.startsWith("### ")
                && !l.startsWith("### Permissions")
                && !l.startsWith("### Legacy Okapi")).count();
        long listed = md.toString().lines().filter(l -> l.startsWith("- `")).count();
        boolean permissions = md.indexOf("### Legacy Okapi permissions") >= 0;
        return "%s — %d fragment(s) inlined, %d listed%s"
                .formatted(tier, inlined, listed, permissions ? ", permissions matched" : "");
    }

    private static Fragments unavailable(String reason) {
        return new Fragments("# Fragment Library\n\n_Unavailable — " + reason + ". "
                + "Automation notes and the `automatable` flag are best-effort; "
                + "flag this in `# Analysis` so the reviewer verifies them._\n",
                "unavailable — " + reason);
    }

    // ── Local filesystem path ──────────────────────────────────────────────────

    private void appendAreaSectionLocal(StringBuilder md, String area, List<String> keywords) {
        md.append("## Area: ").append(area).append("\n\n");

        List<Path> fragmentDirs = dirsFor(area).stream()
                .map(dir -> tafSync.tafRoot().resolve(FRAGMENTS_BASE).resolve(dir))
                .filter(path -> Files.isDirectory(path) || Files.isRegularFile(path))
                .toList();

        if (!fragmentDirs.isEmpty()) {
            appendFragmentsLocal(md, fragmentDirs, keywords);
        } else {
            md.append("_No fragment directory found at ").append(dirsFor(area).stream()
                    .map(dir -> "`" + FRAGMENTS_BASE + "/" + dir + "`").collect(Collectors.joining(", ")))
                    .append("._\n\n");
        }

        appendPermissionsLocal(md, area);
    }

    private void appendFragmentsLocal(StringBuilder md, List<Path> fragmentDirs, List<String> keywords) {
        List<Path> jsFiles = new ArrayList<>();
        for (Path fragmentsDir : fragmentDirs) {
            if (Files.isRegularFile(fragmentsDir)) {
                jsFiles.add(fragmentsDir);
                continue;
            }
            try (Stream<Path> stream = Files.list(fragmentsDir)) {
                stream.filter(p -> p.toString().endsWith(".js") && Files.isRegularFile(p))
                        .forEach(jsFiles::add);
            } catch (IOException e) {
                md.append("_Could not list fragments: ").append(e.getMessage()).append("_\n\n");
                return;
            }
        }
        if (jsFiles.isEmpty()) {
            md.append("_No .js fragment files found._\n\n");
            return;
        }

        List<ScoredFile> scored = jsFiles.stream()
                .map(p -> new ScoredFile(p, scoreLocal(p, keywords)))
                .sorted(Comparator.comparingInt(ScoredFile::score).reversed())
                .toList();

        int cut = Math.min(MAX_FETCHED_FILES, scored.size());
        List<ScoredFile> toFetch = scored.subList(0, cut);
        List<ScoredFile> others = scored.subList(cut, scored.size());

        for (ScoredFile sf : toFetch) {
            try {
                String content = Files.readString(sf.path());
                String relPath = tafSync.tafRoot().relativize(sf.path()).toString().replace('\\', '/');
                md.append("### ").append(relPath).append("\n\n");
                md.append("```js\n").append(content).append("\n```\n\n");
            } catch (IOException e) {
                log.debug("[fragments] Could not read {}: {}", sf.path(), e.getMessage());
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
            content = Files.readString(file).toLowerCase(Locale.ROOT);
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
                md.append("### Legacy Okapi permissions (cross-reference only)\n\n");
                md.append("`gui` labels from the TAF permission dictionary. These are **permissions, "
                        + "not Capability Sets**. Use them in preconditions only when the area "
                        + "context file offers no Capability Sets, and then label them as "
                        + "permissions:\n\n");
                matched.forEach(p -> md.append("- `").append(p).append("`\n"));
                md.append("\n");
            }
        } catch (IOException e) {
            log.debug("[fragments] Could not read permissions.js: {}", e.getMessage());
        }
    }

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
        List<String> dirPaths = dirsFor(area).stream().map(dir -> FRAGMENTS_BASE + "/" + dir).toList();
        List<String> jsPaths = new ArrayList<>();
        for (String dirPath : dirPaths) {
            if (dirPath.endsWith(".js")) {
                jsPaths.add(dirPath);
                continue;
            }
            gitHub.listDirectory(TAF_REPO, TAF_BRANCH, dirPath).stream()
                    .filter(f -> f.endsWith(".js"))
                    .forEach(f -> jsPaths.add(dirPath + "/" + f));
        }

        md.append("## Area: ").append(area).append("\n\n");

        if (jsPaths.isEmpty()) {
            md.append("_No fragment files found in ").append(dirPaths.stream()
                    .map(d -> "`" + d + "`").collect(Collectors.joining(", "))).append("._\n\n");
            return;
        }

        List<String> ranked = jsPaths.stream()
                .sorted(Comparator.comparingInt((String f) ->
                        scoreRemote(f.substring(f.lastIndexOf('/') + 1), keywords)).reversed())
                .toList();
        int cut = Math.min(MAX_FETCHED_FILES, ranked.size());
        List<String> toFetch = ranked.subList(0, cut);
        List<String> others = ranked.subList(cut, ranked.size());

        for (String path : toFetch) {
            String content = gitHub.getFileContent(TAF_REPO, TAF_BRANCH, path);
            if (content != null && !content.isBlank()) {
                md.append("### ").append(path).append("\n\n");
                md.append("```js\n").append(content).append("\n```\n\n");
            }
        }
        if (!others.isEmpty()) {
            md.append("**Other fragments in this area:**\n");
            others.forEach(f -> md.append("- `").append(f.substring(f.lastIndexOf('/') + 1)).append("`\n"));
            md.append("\n");
        }
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

    private List<String> extractKeywords(String summary) {
        if (summary == null || summary.isBlank()) return List.of();
        return Arrays.stream(summary.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(w -> w.length() >= 3 && !STOPWORDS.contains(w))
                .distinct()
                .toList();
    }

    private String normalizeFilename(String filename) {
        return filename.replaceAll("\\.js$", "").toLowerCase(Locale.ROOT)
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[_\\-]", " ");
    }

    private record ScoredFile(Path path, int score) {}
}
