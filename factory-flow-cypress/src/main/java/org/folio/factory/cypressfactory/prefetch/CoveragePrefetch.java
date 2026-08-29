package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.testrail.TestRailCase;
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

    private static final Pattern PERM_LINE = Pattern.compile(
            "^[-*\\d.\\s]*([A-Z][A-Za-z /&-]{2,40}:\\s+[A-Za-z][^\\n]{3,80})$");

    private final TestRailConnector testRail;
    private final CypressFlowProperties properties;

    public CoveragePrefetch(TestRailConnector testRail, CypressFlowProperties properties) {
        this.testRail = testRail;
        this.properties = properties;
    }

    /** Never throws — coverage is a soft dependency. */
    public String load(List<String> areas) {
        try {
            return doLoad(areas);
        } catch (Exception e) {
            log.warn("[coverage] Load failed: {}", e.getMessage());
            return empty("coverage lookup failed: " + e.getMessage());
        }
    }

    private String doLoad(List<String> areas) {
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
        return render(new ArrayList<>(caseMap.values()), caseSection, meaningful, matched);
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
        }

        Set<String> permissions = extractPermissions(cases);
        if (!permissions.isEmpty()) {
            sb.append("\n## Permissions referenced in existing cases\n\n");
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

    private String empty(String reason) {
        return "# Existing Test Coverage\n\n_" + reason + ". No existing coverage loaded._\n";
    }
}
