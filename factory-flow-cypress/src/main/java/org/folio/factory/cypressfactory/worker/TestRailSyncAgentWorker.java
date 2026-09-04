package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.cypressfactory.plan.DevTeamCatalog;
import org.folio.factory.cypressfactory.plan.TestPlanParser;
import org.folio.factory.cypressfactory.plan.TestPlanParser.ParsedCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestRailSyncAgentWorker implements AgentWorker {

    public static final String ID = "cypress-testrail-sync";

    private static final Logger log = LoggerFactory.getLogger(TestRailSyncAgentWorker.class);
    private static final Map<String, Integer> TIER_TO_TEST_GROUP = Map.of(
            "smoke", 1,
            "criticalpath", 2, "critical", 2,
            "extendedpath", 3, "extended", 3,
            "medium", 2);
    private final TestRailConnector testRail;
    private final FrontmatterCodec frontmatterCodec;
    private final CypressFlowProperties properties;
    private final TestPlanParser parser;
    private final DevTeamCatalog devTeams;

    public TestRailSyncAgentWorker(TestRailConnector testRail, FrontmatterCodec frontmatterCodec,
                                   CypressFlowProperties properties, TestPlanParser parser,
                                   DevTeamCatalog devTeams) {
        this.testRail = testRail;
        this.frontmatterCodec = frontmatterCodec;
        this.properties = properties;
        this.parser = parser;
        this.devTeams = devTeams;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String testPlan = context.requireInput("test_plan.md").content();

        Frontmatter fm = frontmatterCodec.parse(testPlan);
        JsonNode planFm = fm.metadata();

        List<ParsedCase> cases = parser.parse(fm.body());
        if (cases.isEmpty()) {
            log.info("No cases found under '{}' in test_plan.md - skipping TestRail sync",
                    TestPlanParser.CASES_HEADING);
            return AgentResult.of("test_plan.md", testPlan);
        }

        long sectionId = resolveSectionId(planFm);
        if (sectionId == 0L) {
            log.info("TestRail section not configured (set FACTORY_CYPRESS_TESTRAIL_SECTION_ID or add testrail_section to test_plan.md frontmatter) - skipping TestRail sync");
            return AgentResult.of("test_plan.md", testPlan);
        }

        long cypressLabelId = resolveCypressLabel();
        Integer devTeamId = resolveDevTeamId(planFm.path("team").asString(""));
        String issueKey = planFm.path("issue_key").asString("");
        String refs = resolveRefs(planFm.path("refs").asString(""), issueKey);

        Map<String, String> idMap = new HashMap<>();
        for (ParsedCase tc : cases) {
            boolean tcEcs = tc.ecsEnabled();
            Map<String, Object> metadata = buildMetadata(tc, tcEcs, cypressLabelId, devTeamId, refs);
            try {
                long caseId = testRail.addCaseWithMetadata(
                        sectionId, tc.title(), null, null, metadata);
                String cId = "C" + caseId;
                idMap.put(tc.id(), cId);
                log.info("Created TestRail case {} for {}", cId, tc.id());
            } catch (Exception e) {
                log.warn("Failed to create TestRail case for {} ({}): {}", tc.id(), tc.title(), e.getMessage());
            }
        }

        String updatedBody = replaceIds(fm.body(), idMap);
        String updatedContent = frontmatterCodec.render(fm.metadata(), updatedBody);
        return AgentResult.of("test_plan.md", updatedContent);
    }

    private long resolveSectionId(JsonNode planFm) {
        String section = planFm.path("testrail_section").asString("");
        if (!section.isBlank()) return parseOrResolveSectionRef(section.trim());
        Long defaultId = properties.testrailSectionId();
        return defaultId != null ? defaultId : 0L;
    }

    /**
     * TestRail's References field must never lose the originating ticket. The generator
     * already guarantees this, but a reviewer can edit the frontmatter at gate 1, so the
     * key is re-asserted here.
     */
    static String resolveRefs(String refs, String issueKey) {
        if (issueKey == null || issueKey.isBlank()) return refs == null ? "" : refs.strip();
        if (refs == null || refs.isBlank()) return issueKey;
        boolean present = Arrays.stream(refs.split(","))
                .map(String::strip)
                .anyMatch(k -> k.equalsIgnoreCase(issueKey));
        return present ? refs.strip() : issueKey + ", " + refs.strip();
    }

    /**
     * Three outcomes, deliberately distinguished. An unset field is invisible in TestRail, so
     * the only thing worse than getting the team wrong is dropping it without saying so.
     */
    private Integer resolveDevTeamId(String team) {
        if (team == null || team.isBlank()) {
            log.warn("No dev team on the test plan - TestRail Dev Team will be left unset");
            return null;
        }
        Optional<DevTeamCatalog.DevTeam> known = devTeams.find(team);
        if (known.isEmpty()) {
            log.warn("Dev team '{}' is not in references/dev-teams.md - TestRail Dev Team left "
                    + "unset. Add the team there if it is a real FOLIO team.", team);
            return null;
        }
        Integer id = known.get().testRailId();
        if (id == null) {
            log.warn("Dev team '{}' has no TestRail id in references/dev-teams.md - TestRail Dev "
                    + "Team left unset. Fill the TestRail ID column in for this team.",
                    known.get().jiraName());
        }
        return id;
    }

    private long parseOrResolveSectionRef(String ref) {
        try {
            return Long.parseLong(ref);
        } catch (NumberFormatException ignored) {
        }
        try {
            return testRail.resolveSectionByPath(ref);
        } catch (Exception e) {
            throw new AgentExecutionException(
                    "Cannot resolve TestRail section '" + ref + "': " + e.getMessage(), e);
        }
    }

    private long resolveCypressLabel() {
        String label = properties.testrailLabel();
        try {
            return testRail.getOrCreateLabel(label);
        } catch (Exception e) {
            throw new AgentExecutionException(
                    "Cannot resolve '" + label + "' TestRail label — create it manually or check credentials: "
                            + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildMetadata(ParsedCase tc, boolean ecsEnabled, long cypressLabelId,
                                               Integer devTeamId, String refs) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("template_id", 2);
        meta.put("custom_automation_type", 2);
        meta.put("custom_ecs_enabled", ecsEnabled);
        meta.put("priority_id", properties.testrailPriorityId());
        meta.put("type_id", properties.testrailCaseTypeId());
        meta.put("labels", List.of(cypressLabelId));
        if (devTeamId != null) {
            meta.put("custom_dev_team", devTeamId);
        }
        if (!refs.isBlank()) {
            meta.put("refs", refs);
        }
        if (!tc.preconditions().isEmpty()) {
            meta.put("custom_preconds", toHtmlList(tc.preconditions()));
        }
        meta.put("custom_steps_separated", buildSteps(tc.steps()));
        meta.put("custom_test_group", mapTestGroup(tc.testGroup()));
        meta.put("custom_user_journey", tc.userJourney());
        return meta;
    }

    private List<Map<String, Object>> buildSteps(List<ParsedCase.StepRow> steps) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ParsedCase.StepRow step : steps) {
            Map<String, Object> row = new HashMap<>();
            row.put("content", wrap(step.action()));
            row.put("expected", wrap(step.expected()));
            row.put("additional_info", "");
            row.put("refs", "");
            row.put("markdown_editor_id", 1);
            result.add(row);
        }
        return result;
    }

    private int mapTestGroup(String testGroup) {
        Integer id = TIER_TO_TEST_GROUP.get(normalizeTier(testGroup));
        if (id != null) return id;
        log.warn("Test Group '{}' is not a known tier - defaulting to Critical Path. "
                + "Valid values: Smoke, Critical Path, Extended.", testGroup);
        return 2;
    }

    /** Shared with the generation worker so both agree on what counts as a known tier. */
    public static String normalizeTier(String testGroup) {
        return testGroup == null ? "" : testGroup.toLowerCase(Locale.ROOT).replace(" ", "");
    }

    public static boolean isKnownTier(String testGroup) {
        return TIER_TO_TEST_GROUP.containsKey(normalizeTier(testGroup));
    }

    private String toHtmlList(List<String> items) {
        StringBuilder sb = new StringBuilder("<ol>");
        for (String item : items) {
            sb.append("<li>").append(toHtmlListItem(item)).append("</li>");
        }
        sb.append("</ol>");
        return sb.toString();
    }

    private String toHtmlListItem(String item) {
        if (!item.contains("\n")) return htmlEscape(item);
        String[] lines = item.split("\n", -1);
        StringBuilder sb = new StringBuilder(htmlEscape(lines[0]));
        for (int i = 1; i < lines.length; i++) {
            sb.append("<br>").append(htmlEscape(lines[i]));
        }
        return sb.toString();
    }

    private static String wrap(String text) {
        if (text == null || text.isEmpty()) return "<p></p>";
        if (!text.contains("\n")) return "<p>" + htmlEscape(text) + "</p>";
        return renderMultiLine(text);
    }

    private static String renderMultiLine(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        List<String> listItems = null;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("- ")) {
                if (listItems == null) listItems = new ArrayList<>();
                listItems.add(trimmed.substring(2).strip());
            } else {
                if (listItems != null) {
                    sb.append("<ul>");
                    for (String item : listItems) sb.append("<li>").append(htmlEscape(item)).append("</li>");
                    sb.append("</ul>");
                    listItems = null;
                }
                if (!trimmed.isEmpty()) sb.append("<p>").append(htmlEscape(trimmed)).append("</p>");
            }
        }
        if (listItems != null) {
            sb.append("<ul>");
            for (String item : listItems) sb.append("<li>").append(htmlEscape(item)).append("</li>");
            sb.append("</ul>");
        }
        return sb.toString();
    }

    private static String htmlEscape(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String replaceIds(String body, Map<String, String> idMap) {
        if (idMap.isEmpty() || body == null) {
            return body;
        }
        String result = body;
        for (Map.Entry<String, String> entry : idMap.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
