package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.connectors.testrail.TestRailFieldOption;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.cypressfactory.plan.DevTeamCatalog;
import org.folio.factory.cypressfactory.plan.MarcFixtureBuilder;
import org.folio.factory.cypressfactory.plan.TestPlanParser;
import org.folio.factory.cypressfactory.plan.TestPlanParser.AttachmentKind;
import org.folio.factory.cypressfactory.plan.TestPlanParser.ParsedCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;
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
    private static final Pattern ORDINAL_PREFIX = Pattern.compile("^\\d+\\s*-\\s*");

    /**
     * Resolved lazily on first use, not at app startup: this app is long-running, so an
     * eager fetch would go stale the next time TestRail's admin re-templates the field, and
     * would make app boot depend on TestRail being reachable. A benign race between concurrent
     * executions on first use is acceptable - both resolve the same id and the second write
     * just repeats the first.
     */
    private volatile Long cachedCaseTypeId;
    private volatile Long cachedPriorityId;

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

        Map<String, String> idMap = new HashMap<>();
        for (ParsedCase tc : cases) {
            boolean tcEcs = tc.ecsEnabled();
            String caseRefs = TestCaseGenAgentWorker.buildRefs(
                    issueKey, tc.refs(), properties.refsExcludedProjects()).joined();
            Map<String, Object> metadata = buildMetadata(tc, tcEcs, cypressLabelId, devTeamId, caseRefs);
            try {
                long caseId = testRail.addCaseWithMetadata(
                        sectionId, tc.title(), null, null, metadata);
                String cId = "C" + caseId;
                idMap.put(tc.id(), cId);
                log.info("Created TestRail case {} for {}", cId, tc.id());
                uploadAttachment(caseId, cId, tc);
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

    private long resolveCaseTypeId() {
        Long cached = cachedCaseTypeId;
        if (cached != null) return cached;
        long id = resolveFieldOptionId(properties.testrailCaseTypeName(), this::fetchCaseTypes, "case type");
        cachedCaseTypeId = id;
        return id;
    }

    private long resolvePriorityId() {
        Long cached = cachedPriorityId;
        if (cached != null) return cached;
        long id = resolveFieldOptionId(properties.testrailPriorityName(), this::fetchPriorities, "priority");
        cachedPriorityId = id;
        return id;
    }

    private List<TestRailFieldOption> fetchCaseTypes() {
        return testRail.getCaseTypes();
    }

    private List<TestRailFieldOption> fetchPriorities() {
        return testRail.getPriorities();
    }

    private long resolveFieldOptionId(String configuredName, Supplier<List<TestRailFieldOption>> fetch,
                                       String kind) {
        List<TestRailFieldOption> options;
        try {
            options = fetch.get();
        } catch (Exception e) {
            throw new AgentExecutionException(
                    "Cannot fetch TestRail " + kind + " options: " + e.getMessage(), e);
        }
        String target = normalizeFieldName(configuredName);
        for (TestRailFieldOption o : options) {
            if (target.equals(normalizeFieldName(o.name()))
                    || (o.shortName() != null && target.equals(normalizeFieldName(o.shortName())))) {
                return o.id();
            }
        }
        throw new AgentExecutionException(
                "Cannot resolve TestRail " + kind + " '" + configuredName + "' - checked "
                        + options.size() + " option(s) from TestRail. Configured "
                        + "FACTORY_CYPRESS_TESTRAIL_" + (kind.equals("case type") ? "CASE_TYPE" : "PRIORITY")
                        + "_NAME must match a real TestRail " + kind + " name.");
    }

    /** Strips a leading ordinal prefix ("2 - Medium" -> "medium") before case-insensitive compare. */
    private static String normalizeFieldName(String s) {
        return s == null ? "" : ORDINAL_PREFIX.matcher(s.strip()).replaceFirst("").toLowerCase(Locale.ROOT);
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

    /**
     * Built from whatever the approved {@code test_plan.md} says, not cached from generation
     * time — a human can amend the attachment attribute or content at gate 1, so this is the
     * only point the bytes actually uploaded are derived from the approved artifact. Degrades
     * the same way every other TestRail write in this loop does: a failure here logs and moves
     * on, it never fails the whole sync over one case's fixture.
     *
     * <p>For MARC, the model writes its own internal case id ({@code tc.id()}, e.g.
     * {@code TC004}) as the key-field value (245 $a for bib, 1XX $a/$t for authority) because
     * the real TestRail id doesn't exist yet at generation time. Substituted here, mechanically,
     * for the real one — the same substitution {@link #replaceIds} later applies to the plan
     * body, just applied to the fixture bytes before they're built and uploaded, since this
     * runs inside the per-case loop, before that single pass over the body.</p>
     */
    private void uploadAttachment(long caseId, String cId, ParsedCase tc) {
        AttachmentKind kind = tc.attachmentKind();
        if (kind == null || tc.attachmentContent().isBlank()) return;

        if (!kind.isMarc()) {
            byte[] bytes = tc.attachmentContent().getBytes(StandardCharsets.UTF_8);
            uploadOne(caseId, cId, cId + kind.extension(), bytes);
            return;
        }

        List<String> blocks = MarcFixtureBuilder.splitRecords(tc.attachmentContent());
        boolean multi = blocks.size() > 1;
        String label = recordTypeLabel(kind);
        for (int i = 0; i < blocks.size(); i++) {
            String suffix = multi ? "_" + (i + 1) : "";
            String content = blocks.get(i).replace(tc.id(), cId);
            if (multi) {
                content = content.replace(cId + "_" + label, cId + "_" + label + suffix);
            }
            MarcFixtureBuilder.Result result = MarcFixtureBuilder.build(content, kind);
            if (!result.valid()) {
                log.warn("Skipping attachment {} for {} ({}): invalid MARC content - {}",
                        multi ? "record " + (i + 1) : "", cId, tc.id(), result.errors());
                continue;
            }
            uploadOne(caseId, cId, cId + label + suffix + kind.extension(), result.bytes());
        }
    }

    private void uploadOne(long caseId, String cId, String filename, byte[] bytes) {
        try {
            testRail.addAttachmentToCase(caseId, bytes, filename);
            log.info("Uploaded attachment {} to TestRail case {}", filename, cId);
        } catch (Exception e) {
            log.warn("Failed to upload attachment {} to TestRail case {}: {}", filename, cId, e.getMessage());
        }
    }

    private static String recordTypeLabel(AttachmentKind kind) {
        return switch (kind) {
            case MARC_BIB -> "MarcBibInstance";
            case MARC_AUTHORITY -> "MarcAuthority";
            case MARC_HOLDINGS -> "MarcHoldings";
            default -> throw new IllegalArgumentException("not a MARC kind: " + kind);
        };
    }

    private Map<String, Object> buildMetadata(ParsedCase tc, boolean ecsEnabled, long cypressLabelId,
                                               Integer devTeamId, String refs) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("template_id", 2);
        meta.put("custom_automation_type", 2);
        meta.put("custom_ecs_enabled", ecsEnabled);
        meta.put("priority_id", resolvePriorityId());
        meta.put("type_id", resolveCaseTypeId());
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
