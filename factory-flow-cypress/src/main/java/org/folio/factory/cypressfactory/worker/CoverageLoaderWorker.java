package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.testrail.TestRailCase;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.connectors.testrail.TestRailSectionRef;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches existing TestRail cases from area-relevant sections and renders them as
 * {@code existing_coverage.md}. The scenario-analysis agent uses this to avoid
 * proposing duplicate scenarios.
 *
 * <p>Area detection: reads the {@code areas} list from {@code context.md} frontmatter
 * (produced by {@code ContextLoaderWorker}) and keyword-matches it against all TestRail
 * section paths. Only sections whose full path contains all significant keywords from the
 * area name are included. The placement-fallback section
 * ({@code FACTORY_CYPRESS_TESTRAIL_SECTION_ID}) is explicitly excluded — it is a
 * non-thematic staging section, not an area-specific folder.</p>
 *
 * <p>Degrades gracefully: returns an empty-coverage document when TestRail is not
 * configured, the area is unknown, or no matching section is found.</p>
 */
public class CoverageLoaderWorker implements AgentWorker {

    public static final String ID = "cypress-coverage-loader";

    private static final Logger log = LoggerFactory.getLogger(CoverageLoaderWorker.class);
    private static final int MAX_CASES = 150;
    private static final int MIN_KEYWORD_LENGTH = 3;

    private static final Pattern PERM_LINE = Pattern.compile(
            "^[-*\\d.\\s]*([A-Z][A-Za-z /&-]{2,40}:\\s+[A-Za-z][^\\n]{3,80})$");

    private final TestRailConnector testRail;
    private final FrontmatterCodec frontmatterCodec;
    private final CypressFlowProperties properties;

    public CoverageLoaderWorker(TestRailConnector testRail, FrontmatterCodec frontmatterCodec,
                                CypressFlowProperties properties) {
        this.testRail = testRail;
        this.frontmatterCodec = frontmatterCodec;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String contextMd = context.requireInput("context.md").content();
        List<String> areas = parseAreas(contextMd);

        List<String> meaningful = areas.stream().filter(a -> !"generic".equals(a)).toList();
        if (meaningful.isEmpty()) {
            log.info("Area not detected in context.md - skipping coverage scan");
            return AgentResult.of("existing_coverage.md",
                    empty("Area not detected — coverage scan skipped"));
        }

        List<TestRailSectionRef> allSections;
        try {
            allSections = testRail.getSections();
        } catch (Exception e) {
            log.warn("Could not fetch TestRail sections: {}", e.getMessage());
            return AgentResult.of("existing_coverage.md",
                    empty("TestRail unavailable: " + e.getMessage()));
        }

        if (allSections.isEmpty()) {
            log.info("TestRail not configured or project has no sections - skipping coverage scan");
            return AgentResult.of("existing_coverage.md",
                    empty("TestRail not configured or no sections available — coverage scan skipped"));
        }

        Long fallbackId = properties.testrailSectionId();
        Map<Long, String> sectionPaths = new LinkedHashMap<>();
        for (TestRailSectionRef s : allSections) sectionPaths.put(s.id(), s.fullPath());

        List<TestRailSectionRef> matched = findMatchingSections(meaningful, allSections);
        // Exclude the placement-fallback section — it is non-thematic
        if (fallbackId != null) {
            matched = matched.stream().filter(s -> s.id() != fallbackId).toList();
        }

        if (matched.isEmpty()) {
            String fallbackDesc = fallbackId != null
                    ? "'" + sectionPaths.getOrDefault(fallbackId, "test_ai") + "' (section " + fallbackId + ")"
                    : null;
            String reason = "No area-specific TestRail section found for: " + meaningful;
            if (fallbackDesc != null) {
                reason += " — " + fallbackDesc + " is the placement fallback (non-thematic, not scanned)";
            }
            log.info(reason);
            return AgentResult.of("existing_coverage.md", empty(reason));
        }

        log.info("Coverage scan: area(s) {} matched {} section(s): {}", meaningful, matched.size(),
                matched.stream().map(s -> safeLog(s.fullPath())).toList());

        // Load cases from all matched sections; deduplicate by case ID
        Map<Long, TestRailCase> caseMap = new LinkedHashMap<>();
        Map<Long, String> caseSection = new LinkedHashMap<>();
        for (TestRailSectionRef sec : matched) {
            if (caseMap.size() >= MAX_CASES) break;
            try {
                List<TestRailCase> cases = testRail.getCases(sec.id());
                for (TestRailCase c : cases) {
                    if (!caseMap.containsKey(c.id()) && caseMap.size() < MAX_CASES) {
                        caseMap.put(c.id(), c);
                        caseSection.put(c.id(), sec.fullPath());
                    }
                }
            } catch (Exception e) {
                log.warn("Could not fetch cases from section '{}' ({}): {}", safeLog(sec.fullPath()), sec.id(), e.getMessage());
            }
        }

        if (caseMap.isEmpty()) {
            log.info("No existing cases found in matched sections for area(s): {}", meaningful);
            return AgentResult.of("existing_coverage.md",
                    empty("No cases found in matched sections for: " + meaningful));
        }

        log.info("Loaded {} existing case(s) from {} section(s)", caseMap.size(), matched.size());
        return AgentResult.of("existing_coverage.md",
                render(new ArrayList<>(caseMap.values()), caseSection, meaningful, matched));
    }

    private List<String> parseAreas(String contextMd) {
        JsonNode fm = frontmatterCodec.parse(contextMd).metadata();
        JsonNode areasNode = fm.path("areas");
        List<String> areas = new ArrayList<>();
        for (JsonNode a : areasNode) {
            String area = a.asString("").strip();
            if (!area.isEmpty()) areas.add(area);
        }
        return areas;
    }

    private List<TestRailSectionRef> findMatchingSections(List<String> areas, List<TestRailSectionRef> all) {
        Set<Long> matched = new LinkedHashSet<>();
        for (String area : areas) {
            List<String> keywords = Arrays.stream(area.split("[\\-_]"))
                    .filter(k -> k.length() >= MIN_KEYWORD_LENGTH)
                    .toList();
            if (keywords.isEmpty()) continue;
            for (TestRailSectionRef s : all) {
                String pathLower = s.fullPath().toLowerCase();
                if (keywords.stream().allMatch(k -> pathLower.contains(k.toLowerCase()))) {
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
