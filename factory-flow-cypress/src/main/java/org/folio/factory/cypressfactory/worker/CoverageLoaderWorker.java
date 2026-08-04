package org.folio.factory.cypressfactory.worker;

import org.folio.factory.connectors.testrail.TestRailCase;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches existing TestRail cases from the configured section and renders them as
 * {@code existing_coverage.md}. The scenario-analysis agent uses this to avoid
 * proposing duplicate scenarios and to discover what permissions the AQA team
 * has already validated for the area.
 *
 * <p>Degrades gracefully: returns an empty-coverage document when TestRail is not
 * configured or the section ID is unset — downstream agents handle the missing data.</p>
 */
public class CoverageLoaderWorker implements AgentWorker {

    public static final String ID = "cypress-coverage-loader";

    private static final Logger log = LoggerFactory.getLogger(CoverageLoaderWorker.class);
    private static final int MAX_CASES = 150;

    // Matches lines that look like "Area: description" — the displayName format from package.json
    // and from TestRail precondition lines such as "- Inventory: View, create, edit, delete"
    private static final Pattern PERM_LINE = Pattern.compile(
            "^[-*\\d.\\s]*([A-Z][A-Za-z /&-]{2,40}:\\s+[A-Za-z][^\\n]{3,80})$");

    private final TestRailConnector testRail;
    private final CypressFlowProperties properties;

    public CoverageLoaderWorker(TestRailConnector testRail, CypressFlowProperties properties) {
        this.testRail = testRail;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        Long sectionId = properties.testrailSectionId();
        if (sectionId == null) {
            log.info("FACTORY_CYPRESS_TESTRAIL_SECTION_ID not configured — skipping coverage scan");
            return AgentResult.of("existing_coverage.md",
                    empty("FACTORY_CYPRESS_TESTRAIL_SECTION_ID not configured"));
        }

        List<TestRailCase> cases;
        try {
            cases = testRail.getCases(sectionId);
        } catch (Exception e) {
            log.warn("Could not fetch TestRail cases for section {}: {}", sectionId, e.getMessage());
            return AgentResult.of("existing_coverage.md",
                    empty("TestRail unavailable: " + e.getMessage()));
        }

        if (cases.isEmpty()) {
            log.info("No existing cases in TestRail section {}", sectionId);
            return AgentResult.of("existing_coverage.md",
                    empty("No cases found in section " + sectionId));
        }

        log.info("Loaded {} existing case(s) from TestRail section {}", cases.size(), sectionId);
        List<TestRailCase> shown = cases.size() > MAX_CASES ? cases.subList(0, MAX_CASES) : cases;
        return AgentResult.of("existing_coverage.md", render(shown, sectionId, cases.size()));
    }

    private String render(List<TestRailCase> cases, long sectionId, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Existing Test Coverage\n\n");
        sb.append("Source: TestRail section ").append(sectionId)
                .append(" — ").append(total).append(" case(s)");
        if (total > cases.size()) {
            sb.append(" (showing first ").append(cases.size()).append(')');
        }
        sb.append(".\n\n");

        sb.append("## Cases\n\n");
        for (TestRailCase c : cases) {
            sb.append("- **C").append(c.id()).append("** — ").append(c.title()).append('\n');
        }

        Set<String> permissions = extractPermissions(cases);
        if (!permissions.isEmpty()) {
            sb.append("\n## Permissions referenced in existing cases\n\n");
            for (String p : permissions) {
                sb.append("- ").append(p).append('\n');
            }
        }

        return sb.toString();
    }

    private Set<String> extractPermissions(List<TestRailCase> cases) {
        Set<String> found = new LinkedHashSet<>();
        for (TestRailCase c : cases) {
            if (c.preconditions().isBlank()) continue;
            for (String line : c.preconditions().split("\n")) {
                Matcher m = PERM_LINE.matcher(line.strip());
                if (m.matches()) {
                    found.add(m.group(1).strip());
                }
            }
        }
        return found;
    }

    private String empty(String reason) {
        return "# Existing Test Coverage\n\n_" + reason + ". No existing coverage loaded._\n";
    }
}
