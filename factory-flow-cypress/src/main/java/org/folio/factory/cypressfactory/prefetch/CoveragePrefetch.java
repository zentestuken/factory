package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.testrail.TestRailCase;
import org.folio.factory.connectors.testrail.TestRailStep;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.connectors.testrail.TestRailSectionRef;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches existing TestRail cases from area-relevant sections so the generator can mark
 * scenarios EXISTING instead of proposing duplicates.
 *
 * <p>Demoted from the former {@code cypress-coverage-loader} worker; section matching,
 * dedup and the 150-case cap are unchanged. The area now arrives directly rather than via
 * {@code context.md} frontmatter. The placement-fallback section
 * ({@code FACTORY_CYPRESS_TESTRAIL_SECTION_ID}) stays excluded — it is non-thematic.</p>
 */
public class CoveragePrefetch {

    private static final Logger log = LoggerFactory.getLogger(CoveragePrefetch.class);
    private static final int MAX_CASES = 150;
    private static final int MIN_KEYWORD_LENGTH = 3;
    private static final int PRECONDITION_PREVIEW = 200;

    private static final Pattern PERM_LINE = Pattern.compile(
            "^[-*\\d.\\s]*([A-Z][A-Za-z /&-]{2,40}:\\s+[A-Za-z][^\\n]{3,80})$");

    private final TestRailConnector testRail;
    private final CypressFlowProperties properties;

    public CoveragePrefetch(TestRailConnector testRail, CypressFlowProperties properties) {
        this.testRail = testRail;
        this.properties = properties;
    }

    /** Never throws — coverage is a soft dependency. */
    public Coverage load(List<String> areas) {
        try {
            return doLoad(areas);
        } catch (Exception e) {
            log.warn("[coverage] Load failed: {}", e.getMessage());
            return empty("coverage lookup failed: " + e.getMessage());
        }
    }

    private Coverage doLoad(List<String> areas) {
        List<String> meaningful = areas == null ? List.of()
                : areas.stream().filter(a -> !"generic".equals(a)).toList();
        if (meaningful.isEmpty()) {
            return empty("Area not detected — coverage scan skipped");
        }

        List<TestRailSectionRef> allSections;
        try {
            allSections = testRail.getSections();
        } catch (Exception e) {
            log.warn("[coverage] Could not fetch TestRail sections: {}", e.getMessage());
            return empty("TestRail unavailable: " + e.getMessage());
        }

        if (allSections.isEmpty()) {
            return empty("TestRail not configured or no sections available — coverage scan skipped");
        }

        Long fallbackId = properties.testrailSectionId();
        Map<Long, String> sectionPaths = new LinkedHashMap<>();
        for (TestRailSectionRef s : allSections) sectionPaths.put(s.id(), s.fullPath());

        List<TestRailSectionRef> matched = findMatchingSections(meaningful, allSections);
        if (fallbackId != null) {
            matched = matched.stream().filter(s -> s.id() != fallbackId).toList();
        }

        if (matched.isEmpty()) {
            String reason = "No area-specific TestRail section found for: " + meaningful;
            if (fallbackId != null) {
                reason += " — '" + sectionPaths.getOrDefault(fallbackId, "test_ai") + "' (section "
                        + fallbackId + ") is the placement fallback (non-thematic, not scanned)";
            }
            log.info("[coverage] {}", reason);
            return empty(reason);
        }

        log.info("[coverage] Area(s) {} matched {} section(s): {}", meaningful, matched.size(),
                matched.stream().map(s -> safeLog(s.fullPath())).toList());

        Map<Long, TestRailCase> caseMap = new LinkedHashMap<>();
        Map<Long, String> caseSection = new LinkedHashMap<>();
        for (TestRailSectionRef sec : matched) {
            if (caseMap.size() >= MAX_CASES) break;
            try {
                for (TestRailCase c : testRail.getCases(sec.id())) {
                    if (!caseMap.containsKey(c.id()) && caseMap.size() < MAX_CASES) {
                        caseMap.put(c.id(), c);
                        caseSection.put(c.id(), sec.fullPath());
                    }
                }
            } catch (Exception e) {
                log.warn("[coverage] Could not fetch cases from section '{}' ({}): {}",
                        safeLog(sec.fullPath()), sec.id(), e.getMessage());
            }
        }

        if (caseMap.isEmpty()) {
            return empty("No cases found in matched sections for: " + meaningful);
        }

        log.info("[coverage] Loaded {} existing case(s) from {} section(s)", caseMap.size(), matched.size());
        return new Coverage(
                render(new ArrayList<>(caseMap.values()), caseSection, meaningful, matched),
                caseMap, caseSection);
    }

    private List<TestRailSectionRef> findMatchingSections(List<String> areas, List<TestRailSectionRef> all) {
        Set<Long> matched = new LinkedHashSet<>();
        for (String area : areas) {
            List<String> keywords = Arrays.stream(area.split("[\\-_]"))
                    .filter(k -> k.length() >= MIN_KEYWORD_LENGTH)
                    .toList();
            if (keywords.isEmpty()) continue;
            for (TestRailSectionRef s : all) {
                String pathLower = s.fullPath().toLowerCase(Locale.ROOT);
                if (keywords.stream().allMatch(k -> pathLower.contains(k.toLowerCase(Locale.ROOT)))) {
                    matched.add(s.id());
                }
            }
        }
        return all.stream().filter(s -> matched.contains(s.id())).toList();
    }

    private String render(List<TestRailCase> cases, Map<Long, String> caseSection,
                          List<String> areas, List<TestRailSectionRef> sections) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Existing Test Coverage\n\n");
        sb.append("Area(s): ").append(areas).append('\n');
        sb.append("Sections scanned:");
        for (TestRailSectionRef s : sections) {
            sb.append("\n- ").append(s.fullPath());
        }
        sb.append("\n\nTotal: ").append(cases.size()).append(" case(s)");
        if (cases.size() >= MAX_CASES) sb.append(" (limit reached — not all cases shown)");
        sb.append(".\n\n");

        sb.append("## Cases\n\n");
        for (TestRailCase c : cases) {
            sb.append("- **C").append(c.id()).append("** — ").append(c.title());
            String section = caseSection.get(c.id());
            if (section != null) sb.append(" _(").append(section).append(")_");
            sb.append('\n');
            String preconds = preview(c.preconditions());
            if (!preconds.isEmpty()) {
                sb.append("  _Preconditions:_ ").append(preconds).append('\n');
            }
        }

        Set<String> permissions = extractPermissions(cases);
        if (!permissions.isEmpty()) {
            sb.append("\n## Access strings seen in existing case preconditions\n\n");
            sb.append("Mixed vintage - some are Eureka Capability Sets, some are legacy Okapi "
                    + "permission display names. Decide which a string is before reusing it, and "
                    + "label it accordingly.\n\n");
            for (String p : permissions) sb.append("- ").append(p).append('\n');
        }

        return sb.toString();
    }

    private Set<String> extractPermissions(List<TestRailCase> cases) {
        Set<String> found = new LinkedHashSet<>();
        for (TestRailCase c : cases) {
            if (c.preconditions().isBlank()) continue;
            for (String line : c.preconditions().split("\n")) {
                Matcher m = PERM_LINE.matcher(line.strip());
                if (m.matches()) found.add(m.group(1).strip());
            }
        }
        return found;
    }

    private static String safeLog(String s) {
        return s.replace("›", ">");
    }

    private Coverage empty(String reason) {
        return Coverage.empty("# Existing Test Coverage\n\n_" + reason
                + ". No existing coverage loaded._\n");
    }

    /**
     * Flattens and truncates a precondition block to one line. Preconditions exist in the index
     * to let triage rule a case out cheaply ("an existing record is open in edit mode" drops a
     * create-story candidate) — the full text is rendered only for nominated candidates.
     */
    static String preview(String text) {
        if (text == null || text.isBlank()) return "";
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= PRECONDITION_PREVIEW
                ? flat
                : flat.substring(0, PRECONDITION_PREVIEW).strip() + "\u2026";
    }

    /**
     * The loaded coverage: the index every run sees, plus the case bodies kept in memory so a
     * candidate can be rendered in full without a second TestRail call — {@code get_cases}
     * already returned everything.
     */
    public record Coverage(String index, Map<Long, TestRailCase> cases, Map<Long, String> sections) {

        public Coverage {
            cases = cases == null ? Map.of() : Map.copyOf(cases);
            sections = sections == null ? Map.of() : Map.copyOf(sections);
        }

        static Coverage empty(String index) {
            return new Coverage(index, Map.of(), Map.of());
        }

        public boolean isEmpty() {
            return cases.isEmpty();
        }

        /**
         * Keeps only ids this run actually loaded, de-duplicated and capped. Triage nominates
         * from the index, so an id outside it is a model error, not a case we should fetch.
         */
        public List<Long> select(List<Long> nominated, int cap) {
            if (nominated == null) return List.of();
            List<Long> kept = new ArrayList<>();
            for (Long id : nominated) {
                if (id != null && cases.containsKey(id) && !kept.contains(id)) {
                    kept.add(id);
                    if (kept.size() >= cap) break;
                }
            }
            return kept;
        }

        /** Full preconditions, steps and expected results — the tier that settles the verdict. */
        public String renderCandidates(List<Long> ids) {
            if (ids == null || ids.isEmpty()) return "";
            StringBuilder sb = new StringBuilder(
                    "\n## Candidate cases read in full\n\n"
                    + "These titles look close to this story. Judge EXISTING against the content "
                    + "below, not the title — a matching title with different preconditions or a "
                    + "different entry point is NOT coverage.\n\n");
            for (Long id : ids) {
                TestRailCase c = cases.get(id);
                if (c == null) continue;
                sb.append("### C").append(c.id()).append(" — ").append(c.title());
                String section = sections.get(c.id());
                if (section != null) sb.append(" _(").append(section).append(")_");
                sb.append("\n\n");

                sb.append("**Preconditions:**\n");
                sb.append(c.preconditions().isBlank() ? "_None recorded._\n"
                        : c.preconditions().strip() + "\n");

                sb.append("\n**Steps:**\n");
                if (c.steps().isEmpty()) {
                    sb.append("_None recorded._\n");
                } else {
                    int n = 0;
                    for (TestRailStep step : c.steps()) {
                        sb.append(++n).append(". **Action:** ").append(step.action()).append('\n');
                        if (!step.expected().isBlank()) {
                            sb.append("   **Expected:** ").append(step.expected()).append('\n');
                        }
                    }
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }
}
