package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final Map<String, Integer> TEAM_TO_DEV_TEAM_ID = Map.of(
            "firebird", 3, "folijet", 4, "spitfire", 6, "thunderjet", 8, "vega", 9,
            "volaris", 13, "citation", 18, "corsair", 19, "eureka", 21, "promin", 27);

    private static final Pattern CASE_HEADING = Pattern.compile("^## (.+?):\\s+(.+)$");
    private static final Pattern ATTR_LINE = Pattern.compile("^- \\*\\*(.+?):\\*\\*\\s*(.*)$");
    private static final Pattern STEP_ACTION = Pattern.compile("^\\d+\\.\\s+\\*\\*Action:\\*\\*\\s*(.*)$");
    private static final Pattern STEP_EXPECTED = Pattern.compile("^\\*\\*Expected:\\*\\*\\s*(.*)$");

    private final TestRailConnector testRail;
    private final FrontmatterCodec frontmatterCodec;
    private final CypressFlowProperties properties;
    private final JsonMapper jsonMapper;

    public TestRailSyncAgentWorker(TestRailConnector testRail, FrontmatterCodec frontmatterCodec,
                                   CypressFlowProperties properties, JsonMapper jsonMapper) {
        this.testRail = testRail;
        this.frontmatterCodec = frontmatterCodec;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    private record ParsedCase(
            String id, String title, String tier, boolean automatable,
            boolean ecsEnabled, List<String> preconditions, List<StepRow> steps, String automationNotes) {
        private record StepRow(String action, String expected) {}
    }

    private enum ParseState { ATTRS, PRECONDITIONS, STEPS, DONE }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String testPlan = context.requireInput("test_plan.md").content();
        String scopeManifest = context.requireInput("scope_manifest.md").content();
        String scenarioList = context.requireInput("scenario_list.md").content();

        Frontmatter fm = frontmatterCodec.parse(testPlan);
        JsonNode scopeFm = frontmatterCodec.parse(scopeManifest).metadata();
        JsonNode scenarioFm = scenarioList.stripLeading().startsWith(FrontmatterCodec.DELIMITER)
                ? frontmatterCodec.parse(scenarioList).metadata()
                : jsonMapper.createObjectNode();
        boolean planEcs = scopeFm.path("ecs_enabled").asBoolean(false);

        List<ParsedCase> cases = parseCasesFromBody(fm.body());
        if (cases.isEmpty()) {
            log.info("No cases found in test_plan.md body — skipping TestRail sync");
            return AgentResult.of("test_plan.md", testPlan);
        }

        long sectionId = resolveSectionId(scopeFm, scenarioFm);
        if (sectionId == 0L) {
            log.info("TestRail section not configured (set FACTORY_CYPRESS_TESTRAIL_SECTION_ID or add testrail_section to scope_manifest.md frontmatter) — skipping TestRail sync");
            return AgentResult.of("test_plan.md", testPlan);
        }

        long cypressLabelId = resolveCypressLabel();
        Integer devTeamId = resolveDevTeamId(scopeFm.path("team").asString(""));
        String issueKey = scopeFm.path("issue_key").asString("");

        Map<String, String> idMap = new HashMap<>();
        for (ParsedCase tc : cases) {
            boolean tcEcs = planEcs || tc.ecsEnabled();
            Map<String, Object> metadata = buildMetadata(tc, tcEcs, cypressLabelId, devTeamId, issueKey);
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

    private long resolveSectionId(JsonNode scopeFm, JsonNode scenarioFm) {
        String override = scopeFm.path("testrail_section").asString("");
        if (!override.isBlank()) return parseOrResolveSectionRef(override.trim());
        String suggestion = scenarioFm.path("testrail_section").asString("");
        if (!suggestion.isBlank()) return parseOrResolveSectionRef(suggestion.trim());
        Long defaultId = properties.testrailSectionId();
        return defaultId != null ? defaultId : 0L;
    }

    private Integer resolveDevTeamId(String team) {
        if (team == null || team.isBlank()) return null;
        return TEAM_TO_DEV_TEAM_ID.get(team.toLowerCase().trim());
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
                                               Integer devTeamId, String issueKey) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("template_id", 2);
        meta.put("custom_automation_type", 2);
        meta.put("custom_ecs_enabled", ecsEnabled);
        meta.put("priority_id", properties.testrailPriorityId());
        if (properties.testrailCaseTypeId() != null) {
            meta.put("type_id", properties.testrailCaseTypeId());
        }
        meta.put("labels", List.of(cypressLabelId));
        if (devTeamId != null) {
            meta.put("custom_dev_team", devTeamId);
        }
        if (!issueKey.isBlank()) {
            meta.put("refs", issueKey);
        }
        if (!tc.preconditions().isEmpty()) {
            meta.put("custom_preconds", toHtmlList(tc.preconditions()));
        }
        meta.put("custom_steps_separated", buildSteps(tc.steps()));
        meta.put("custom_test_group", mapTier(tc.tier()));
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

    private int mapTier(String tier) {
        return TIER_TO_TEST_GROUP.getOrDefault(tier.toLowerCase().replace(" ", ""), 2);
    }

    private String toHtmlList(List<String> items) {
        StringBuilder sb = new StringBuilder("<ol>");
        for (String item : items) {
            sb.append("<li>").append(htmlEscape(item)).append("</li>");
        }
        sb.append("</ol>");
        return sb.toString();
    }

    private static void flushPendingStep(List<ParsedCase.StepRow> steps, String action, StringBuilder expected) {
        if (action == null) return;
        String exp = expected != null ? expected.toString() : "";
        steps.add(new ParsedCase.StepRow(action, exp));
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

    private List<ParsedCase> parseCasesFromBody(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<ParsedCase> result = new ArrayList<>();
        String[] sections = body.split("(?m)^(?=## )");
        for (String section : sections) {
            String[] lines = section.split("\n", -1);
            if (lines.length == 0) continue;
            Matcher heading = CASE_HEADING.matcher(lines[0].strip());
            if (!heading.matches()) continue;

            String caseId = heading.group(1).strip();
            String title = heading.group(2).strip();
            String tier = "medium";
            boolean automatable = false;
            boolean ecsEnabled = false;
            List<String> preconditions = new ArrayList<>();
            List<ParsedCase.StepRow> steps = new ArrayList<>();
            String automationNotes = "";
            String pendingAction = null;
            StringBuilder pendingExpected = null;
            ParseState state = ParseState.ATTRS;

            for (int i = 1; i < lines.length; i++) {
                String stripped = lines[i].strip();
                if (stripped.isEmpty()) continue;

                switch (state) {
                    case ATTRS -> {
                        if (stripped.startsWith("**Preconditions**")) {
                            state = ParseState.PRECONDITIONS;
                        } else if (stripped.startsWith("**Steps**")) {
                            state = ParseState.STEPS;
                        } else {
                            Matcher attr = ATTR_LINE.matcher(stripped);
                            if (attr.matches()) {
                                switch (attr.group(1).toLowerCase()) {
                                    case "tier" -> tier = attr.group(2).strip();
                                    case "automatable" -> automatable = attr.group(2).strip().equalsIgnoreCase("yes");
                                    case "ecs" -> ecsEnabled = attr.group(2).strip().equalsIgnoreCase("yes");
                                }
                            }
                        }
                    }
                    case PRECONDITIONS -> {
                        if (stripped.startsWith("**Steps**")) {
                            state = ParseState.STEPS;
                        } else if (stripped.startsWith("- ")) {
                            preconditions.add(stripped.substring(2).strip());
                        }
                    }
                    case STEPS -> {
                        if (stripped.startsWith("**Automation notes:**")) {
                            flushPendingStep(steps, pendingAction, pendingExpected);
                            pendingAction = null; pendingExpected = null;
                            automationNotes = stripped.replaceFirst("^\\*\\*Automation notes:\\*\\*\\s*", "");
                            state = ParseState.DONE;
                        } else {
                            Matcher actionMatcher = STEP_ACTION.matcher(stripped);
                            Matcher expectedMatcher = STEP_EXPECTED.matcher(stripped);
                            if (actionMatcher.matches()) {
                                flushPendingStep(steps, pendingAction, pendingExpected);
                                pendingAction = actionMatcher.group(1).strip();
                                pendingExpected = null;
                            } else if (expectedMatcher.matches() && pendingAction != null) {
                                pendingExpected = new StringBuilder(expectedMatcher.group(1).strip());
                            } else if (pendingExpected != null && (stripped.startsWith("- ") || !stripped.startsWith("**"))) {
                                pendingExpected.append("\n").append(stripped);
                            } else if (pendingAction != null && pendingExpected == null && (stripped.startsWith("- ") || !stripped.startsWith("**"))) {
                                pendingAction = pendingAction + "\n" + stripped;
                            }
                        }
                    }
                    case DONE -> { /* nothing */ }
                }
            }
            flushPendingStep(steps, pendingAction, pendingExpected);
            pendingAction = null; pendingExpected = null;
            result.add(new ParsedCase(caseId, title, tier, automatable, ecsEnabled,
                    preconditions, steps, automationNotes));
        }
        return result;
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
