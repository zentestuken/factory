package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.cypressfactory.plan.DevTeamCatalog;
import org.folio.factory.cypressfactory.plan.TestPlanParser;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestRailSyncAgentWorkerTest {

    private final FrontmatterCodec codec = new FrontmatterCodec();

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TestRailSyncAgentWorker worker(CapturingConnector connector, Long defaultSectionId) {
        CypressFlowProperties props = new CypressFlowProperties("", "main", "", defaultSectionId, null, null, null, null, null, null, true);
        return new TestRailSyncAgentWorker(connector, codec, props, new TestPlanParser(),
                new DevTeamCatalog());
    }

    private String testPlanWithOneCase() {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_story", "non-ecs", "case_count", 1),
                "## TC-1: Test login\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** Yes\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n" +
                "1. **Action:** click\n" +
                "   **Expected:** done\n");
    }

    private String testPlanWithAmendedBodyTitle() {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_story", "non-ecs", "case_count", 1),
                "## TC-1: New Title\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** Yes\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n" +
                "1. **Action:** click\n" +
                "   **Expected:** done\n");
    }

    private String testPlanWithSpecialChars() {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_story", "non-ecs", "case_count", 1),
                "## TC-1: Test case\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** Yes\n" +
                "- **ECS:** No\n\n" +
                "**Preconditions**\n" +
                "- Role < Admin & Viewer\n\n" +
                "**Steps**\n" +
                "1. **Action:** Navigate to Orders > List\n" +
                "   **Expected:** Count < 5 items\n");
    }

    private String testPlanWithMultiLineExpected() {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_story", "non-ecs", "case_count", 1),
                "## TC-1: Test toolbar\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** Yes\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n" +
                "1. **Action:** Save the record\n" +
                "   **Expected:** Action bar shows:\n" +
                "   - 'Cancel' button is enabled\n" +
                "   - 'Save & keep editing' button is disabled\n");
    }

    private String testPlanWithNestedPreconditions() {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_story", "non-ecs", "case_count", 1),
                "## TC-1: Test case\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** Yes\n" +
                "- **ECS:** No\n\n" +
                "**Preconditions**\n" +
                "- A package record exists\n" +
                "- User has the following Capability Sets:\n" +
                "  - Data - UI-Eholdings Records - Edit\n" +
                "  - Data - UI-Eholdings Records - View\n" +
                "- User is on the eHoldings app\n\n" +
                "**Steps**\n" +
                "1. **Action:** click\n" +
                "   **Expected:** done\n");
    }

    private String testPlanWithTestGroup(String testGroup) {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_story", "non-ecs", "case_count", 1),
                "## TC-1: Test case\n\n" +
                "- **Test Group:** " + testGroup + "\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** No\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n" +
                "1. **Action:** click\n" +
                "   **Expected:** done\n");
    }

    private String testPlanWithUserJourney(boolean journey) {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_story", "non-ecs", "case_count", 1),
                "## TC-1: Test case\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** " + (journey ? "Yes" : "No") + "\n" +
                "- **Automatable:** No\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n" +
                "1. **Action:** click\n" +
                "   **Expected:** done\n");
    }

    private String testPlanNoCases() {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_story", "non-ecs", "case_count", 0),
                "# Cypress Test Plan — ERM-1\n");
    }

    private String testPlanWithOneCaseRefs(String refsLine) {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_story", "non-ecs", "case_count", 1),
                "## TC-1: Test login\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** Yes\n" +
                "- **ECS:** No\n" +
                (refsLine == null ? "" : "- **Refs:** " + refsLine + "\n") +
                "\n**Steps**\n" +
                "1. **Action:** click\n" +
                "   **Expected:** done\n");
    }

    /** Re-renders a plan with extra frontmatter keys merged over the defaults. */
    private String planWithMeta(String plan, String key, Object value) {
        String body = codec.parse(plan).body();
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("issue_key", "ERM-1");
        meta.put("team", "");
        meta.put("ecs_story", "non-ecs");
        meta.put("case_count", 1);
        meta.put(key, value);
        return codec.render(meta, body);
    }

    private AgentContext contextWith(String testPlan) {
        return new AgentContext(UUID.randomUUID(), "testrail-sync",
                Map.of("test_plan.md", new ArtifactContent("test_plan.md", 1, "text/markdown", testPlan)),
                null, Map.of(), List.of("test_plan.md"));
    }

    // -------------------------------------------------------------------------
    // Section resolution priority
    // -------------------------------------------------------------------------

    @Test
    void usesNumericSectionIdFromFrontmatter() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, null).execute(
                contextWith(planWithMeta(testPlanWithOneCase(), "testrail_section", "200")));

        assertThat(connector.capturedSectionId).isEqualTo(200L);
    }

    @Test
    void frontmatterSectionOverridesPropertiesDefault() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(planWithMeta(testPlanWithOneCase(), "testrail_section", "300")));

        assertThat(connector.capturedSectionId).isEqualTo(300L);
    }

    @Test
    void resolvesPathStringFromFrontmatterViaConnector() {
        CapturingConnector connector = new CapturingConnector();
        connector.resolvedSectionId = 500L;
        worker(connector, null).execute(
                contextWith(planWithMeta(testPlanWithOneCase(), "testrail_section",
                        "MARC › MARC authority")));

        assertThat(connector.capturedSectionId).isEqualTo(500L);
    }

    @Test
    void fallsBackToPropertiesDefaultWhenFrontmatterHasNoSection() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(contextWith(testPlanWithOneCase()));

        assertThat(connector.capturedSectionId).isEqualTo(114774L);
    }

    @Test
    void skipsTestrailSyncWhenNothingConfigured() {
        CapturingConnector connector = new CapturingConnector();
        String originalPlan = testPlanWithOneCase();

        AgentResult result = worker(connector, null).execute(
                contextWith(originalPlan));

        assertThat(connector.capturedSectionId).isEqualTo(-1L);
        assertThat(result.outputs().get("test_plan.md")).isEqualTo(originalPlan);
    }

    @Test
    void skipsTestrailSyncWhenNoCasesInTestPlan() {
        CapturingConnector connector = new CapturingConnector();

        AgentResult result = worker(connector, 114774L).execute(
                contextWith(testPlanNoCases()));

        assertThat(connector.capturedSectionId).isEqualTo(-1L);
        assertThat(result.outputs().get("test_plan.md")).isEqualTo(testPlanNoCases());
    }

    // -------------------------------------------------------------------------
    // Team → dev team ID mapping
    // -------------------------------------------------------------------------

    @Test
    void setsDevTeamIdFromKnownTeam() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(planWithMeta(testPlanWithOneCase(), "team", "Bama")));

        assertThat(connector.capturedDevTeamId).isEqualTo(14);
    }

    @Test
    void redirectsSupersededTeamToItsCurrentTeam() {
        // Spitfire and Folijet were absorbed into Promin (dev-teams.md "Superseded by" column).
        // A stale ticket still carrying the old team name must resolve to Promin's id (27),
        // not the old team's own id.
        for (String oldTeam : List.of("Folijet", "Spitfire")) {
            CapturingConnector connector = new CapturingConnector();
            worker(connector, 114774L).execute(
                    contextWith(planWithMeta(testPlanWithOneCase(), "team", oldTeam)));

            assertThat(connector.capturedDevTeamId).as("dev team id for %s", oldTeam).isEqualTo(27);
        }
    }

    @Test
    void setsDevTeamIdCaseInsensitive() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(planWithMeta(testPlanWithOneCase(), "team", "EUREKA")));

        assertThat(connector.capturedDevTeamId).isEqualTo(21);
    }

    @Test
    void resolvesTeamsTheOldHardcodedMapDidNotContain() {
        // The previous table held 10 teams; the other 17 were silently dropped even though
        // they are real FOLIO teams with real TestRail ids.
        for (Map.Entry<String, Integer> expected : Map.of(
                "Bienenvolk", 2, "Odin", 17, "Fenrir", 29, "Klemming", 22, "Big FC", 23).entrySet()) {
            CapturingConnector connector = new CapturingConnector();
            worker(connector, 114774L).execute(
                    contextWith(planWithMeta(testPlanWithOneCase(), "team", expected.getKey())));

            assertThat(connector.capturedDevTeamId)
                    .as("dev team id for %s", expected.getKey())
                    .isEqualTo(expected.getValue());
        }
    }

    @Test
    void resolvesTheTestRailSpellingAndDecoratedValues() {
        CapturingConnector byTestRailName = new CapturingConnector();
        worker(byTestRailName, 114774L).execute(
                contextWith(planWithMeta(testPlanWithOneCase(), "team", "BAMA")));
        assertThat(byTestRailName.capturedDevTeamId).isEqualTo(14);

        CapturingConnector decorated = new CapturingConnector();
        worker(decorated, 114774L).execute(
                contextWith(planWithMeta(testPlanWithOneCase(), "team", "Promin Team")));
        assertThat(decorated.capturedDevTeamId).isEqualTo(27);
    }

    @Test
    void omitsDevTeamIdWhenTeamUnknown() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(planWithMeta(testPlanWithOneCase(), "team", "unknown-squad")));

        assertThat(connector.capturedDevTeamId).isNull();
    }

    @Test
    void omitsDevTeamIdWhenTeamAbsent() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithOneCase()));

        assertThat(connector.capturedDevTeamId).isNull();
    }

    // -------------------------------------------------------------------------
    // Refs field (Jira issue key) - a per-case decision, rolled up per case at sync time
    // -------------------------------------------------------------------------

    @Test
    void refsDefaultsToIssueKeyWhenTheCaseDeclaresNone() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(contextWith(testPlanWithOneCaseRefs(null)));

        assertThat(connector.capturedMetadata.get("refs")).isEqualTo("ERM-1");
    }

    @Test
    void refsPassesThroughTheCasesOwnAdditionalKeys() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(contextWith(testPlanWithOneCaseRefs("MODFIN-391")));

        assertThat(connector.capturedMetadata.get("refs")).isEqualTo("ERM-1, MODFIN-391");
    }

    @Test
    void refsAlwaysLeadsWithTheIssueKeyEvenWhenTheCaseDoesNotRepeatIt() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(contextWith(testPlanWithOneCaseRefs("MODFIN-391, MODFIN-392")));

        assertThat(connector.capturedMetadata.get("refs")).isEqualTo("ERM-1, MODFIN-391, MODFIN-392");
    }

    @Test
    void refsMatchIsCaseInsensitiveSoTheKeyIsNotDuplicated() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(contextWith(testPlanWithOneCaseRefs("erm-1, MODFIN-391")));

        assertThat(connector.capturedMetadata.get("refs")).isEqualTo("ERM-1, MODFIN-391");
    }

    // -------------------------------------------------------------------------
    // Title read from body heading (user edits body at HITL gate)
    // -------------------------------------------------------------------------

    @Test
    void titleIsReadFromBodyHeading() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithAmendedBodyTitle()));

        assertThat(connector.capturedTitle).isEqualTo("New Title");
    }

    // -------------------------------------------------------------------------
    // HTML escaping (Bug: < and > in step text produce malformed HTML in TestRail)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void htmlEscapesSpecialCharsInStepsAndPreconditions() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithSpecialChars()));

        assertThat(connector.capturedPreconds)
                .contains("&lt;").contains("&amp;")
                .doesNotContain("<li>Role < Admin");

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) connector.capturedMetadata.get("custom_steps_separated");
        assertThat(steps).hasSize(1);
        assertThat((String) steps.get(0).get("content")).isEqualTo("<p>Navigate to Orders &gt; List</p>");
        assertThat((String) steps.get(0).get("expected")).isEqualTo("<p>Count &lt; 5 items</p>");
    }

    // -------------------------------------------------------------------------
    // Multi-line expected/action text
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void parsesMultiLineExpectedResult() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithMultiLineExpected()));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) connector.capturedMetadata.get("custom_steps_separated");
        assertThat(steps).hasSize(1);
        assertThat((String) steps.get(0).get("content")).isEqualTo("<p>Save the record</p>");
        assertThat((String) steps.get(0).get("expected"))
                .isEqualTo("<p>Action bar shows:</p><ul><li>'Cancel' button is enabled</li>" +
                           "<li>'Save &amp; keep editing' button is disabled</li></ul>");
    }

    // -------------------------------------------------------------------------
    // Nested preconditions (capability set sub-bullets → <br>- in HTML)
    // -------------------------------------------------------------------------

    @Test
    void rendersNestedCapabilitySetsAsHtmlBrList() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithNestedPreconditions()));

        assertThat(connector.capturedPreconds)
                .isEqualTo("<ol>" +
                           "<li>A package record exists</li>" +
                           "<li>User has the following Capability Sets:<br>- Data - UI-Eholdings Records - Edit<br>- Data - UI-Eholdings Records - View</li>" +
                           "<li>User is on the eHoldings app</li>" +
                           "</ol>");
    }

    // -------------------------------------------------------------------------
    // testGroup → custom_test_group mapping
    // -------------------------------------------------------------------------

    @Test
    void mapsTestGroupSmokeToId1() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithTestGroup("smoke")));

        assertThat(connector.capturedMetadata.get("custom_test_group")).isEqualTo(1);
    }

    @Test
    void mapsTestGroupCriticalToId2() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithTestGroup("critical")));

        assertThat(connector.capturedMetadata.get("custom_test_group")).isEqualTo(2);
    }

    @Test
    void mapsTestGroupExtendedToId3() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithTestGroup("extended")));

        assertThat(connector.capturedMetadata.get("custom_test_group")).isEqualTo(3);
    }

    @Test
    void unknownTestGroupDefaultsToCriticalPath() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithTestGroup("unknown-value")));

        assertThat(connector.capturedMetadata.get("custom_test_group")).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // userJourney → custom_user_journey
    // -------------------------------------------------------------------------

    @Test
    void setsUserJourneyTrueWhenYes() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithUserJourney(true)));

        assertThat(connector.capturedMetadata.get("custom_user_journey")).isEqualTo(true);
    }

    @Test
    void setsUserJourneyFalseWhenNo() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithUserJourney(false)));

        assertThat(connector.capturedMetadata.get("custom_user_journey")).isEqualTo(false);
    }

    @Test
    void defaultsUserJourneyToFalseWhenFieldAbsent() {
        CapturingConnector connector = new CapturingConnector();
        // fixture has no User Journey line at all — use testPlanWithTestGroup which omits it
        // (actually it includes "No", but we test the default via the old Tier-style plan)
        String planNoJourneyLine = codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_story", "non-ecs", "case_count", 1),
                "## TC-1: Test\n\n" +
                "- **Test Group:** critical\n" +
                "- **Automatable:** No\n" +
                "- **ECS:** No\n\n" +
                "**Steps**\n1. **Action:** click\n   **Expected:** done\n");
        worker(connector, 114774L).execute(
                contextWith(planNoJourneyLine));

        assertThat(connector.capturedMetadata.get("custom_user_journey")).isEqualTo(false);
    }

    // -------------------------------------------------------------------------
    // type_id / priority_id resolved by name
    // -------------------------------------------------------------------------

    @Test
    void resolvesTypeIdByNameDefaultFunctional() {
        CapturingConnector connector = new CapturingConnector();
        // props constructed with a blank case-type name — CypressFlowProperties defaults it to
        // "Functional", resolved against the stub's get_case_types (Functional=6, not 1 - proves
        // this isn't just coincidentally matching an id).
        worker(connector, 114774L).execute(
                contextWith(testPlanWithOneCase()));

        assertThat(connector.capturedMetadata.get("type_id")).isEqualTo(6L);
    }

    @Test
    void resolvesPriorityIdByNameDefaultMedium() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithOneCase()));

        assertThat(connector.capturedMetadata.get("priority_id")).isEqualTo(2L);
    }

    // -------------------------------------------------------------------------
    // Attachments
    // -------------------------------------------------------------------------

    private String testPlanWithAttachment(String kindSpec, String contentBlock) {
        return codec.render(
                Map.of("issue_key", "ERM-1", "team", "", "ecs_story", "non-ecs", "case_count", 1),
                "## TC-1: Test case\n\n" +
                "- **Test Group:** critical\n" +
                "- **User Journey:** No\n" +
                "- **Automatable:** Yes\n" +
                "- **ECS:** No\n" +
                "- **Attachment:** kind=" + kindSpec + "\n\n" +
                "**Steps**\n" +
                "1. **Action:** click\n" +
                "   **Expected:** done\n\n" +
                "**Attachment content**\n" +
                "```\n" + contentBlock + "\n```\n");
    }

    @Test
    void uploadsCsvAttachmentVerbatim() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithAttachment("csv", "a,b,c\n1,2,3")));

        assertThat(connector.capturedAttachmentCaseId).isEqualTo(999L);
        assertThat(connector.capturedAttachmentFilename).isEqualTo("C999.csv");
        assertThat(new String(connector.capturedAttachmentBytes, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("a,b,c\n1,2,3");
    }

    @Test
    void uploadsValidMarcAttachmentAsBuiltBytes() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithAttachment("marc-bib", "=245  10$aTest Title")));

        assertThat(connector.capturedAttachmentFilename).isEqualTo("C999MarcBibInstance.mrc");
        assertThat(connector.capturedAttachmentBytes).isNotEmpty();

        org.marc4j.MarcStreamReader reader = new org.marc4j.MarcStreamReader(
                new java.io.ByteArrayInputStream(connector.capturedAttachmentBytes));
        assertThat(reader.hasNext()).isTrue();
    }

    @Test
    void substitutesTheModelsOwnCaseIdForTheRealTestRailIdInMarcContent() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(
                contextWith(testPlanWithAttachment("marc-bib", "=245  10$aTC-1_MarcBibInstance")));

        org.marc4j.MarcStreamReader reader = new org.marc4j.MarcStreamReader(
                new java.io.ByteArrayInputStream(connector.capturedAttachmentBytes));
        assertThat(reader.hasNext()).isTrue();
        org.marc4j.marc.Record record = reader.next();
        assertThat(((org.marc4j.marc.DataField) record.getVariableField("245")).getSubfieldsAsString("a"))
                .isEqualTo("C999_MarcBibInstance");
    }

    @Test
    void multipleRecordsOfTheSameKindAreSuffixedAndUploadedSeparately() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(contextWith(testPlanWithAttachment("marc-bib",
                "=245  10$aTC-1_MarcBibInstance\n\n=245  10$aTC-1_MarcBibInstance")));

        assertThat(connector.capturedAttachmentFilenames)
                .containsExactly("C999MarcBibInstance_1.mrc", "C999MarcBibInstance_2.mrc");
    }

    @Test
    void skipsUploadForInvalidMarcContentButStillCreatesTheCase() {
        CapturingConnector connector = new CapturingConnector();
        AgentResult result = worker(connector, 114774L).execute(
                contextWith(testPlanWithAttachment("marc-bib", "not a valid breaker line")));

        assertThat(connector.capturedAttachmentBytes).isNull();
        assertThat(connector.capturedTitle).isEqualTo("Test case");
        assertThat(result.outputs().get("test_plan.md")).contains("C999");
    }

    @Test
    void aFailedAttachmentUploadDoesNotFailTheCaseSync() {
        CapturingConnector connector = new CapturingConnector();
        connector.throwOnAttachment = true;

        AgentResult result = worker(connector, 114774L).execute(
                contextWith(testPlanWithAttachment("text", "some fixture text")));

        assertThat(result.outputs().get("test_plan.md")).contains("C999");
    }

    @Test
    void caseWithNoAttachmentUploadsNothing() {
        CapturingConnector connector = new CapturingConnector();
        worker(connector, 114774L).execute(contextWith(testPlanWithOneCase()));

        assertThat(connector.capturedAttachmentBytes).isNull();
    }

    // -------------------------------------------------------------------------
    // Hand-off to automation (testrail_cases.json)
    // -------------------------------------------------------------------------

    @Test
    void handsOffTheCreatedCaseWithItsAutomatableFlagAndTheAreas() {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("issue_key", "ERM-1");
        meta.put("testrail_section", "200");
        meta.put("areas", List.of("eureka"));
        String plan = codec.render(meta, """
                ## TC-1: Test login

                - **Test Group:** critical
                - **Automatable:** Yes
                - **ECS:** No

                **Steps**
                1. **Action:** click
                   **Expected:** done

                **Automation notes:** reuse usersCard.js
                """);

        String handoff = worker(new CapturingConnector(), null).execute(contextWith(plan))
                .outputs().get(TestRailSyncAgentWorker.CREATED_CASES);

        assertThat(handoff).contains("\"issueKey\":\"ERM-1\"", "\"sectionId\":200",
                "\"areas\":[\"eureka\"]", "\"caseId\":\"C999\"", "\"planId\":\"TC-1\"",
                "\"automatable\":true", "reuse usersCard.js");
    }

    @Test
    void handsOffOnlyCasesTestRailAcceptedAndAnEmptyListWhenNothingWasSynced() {
        var cases = new TestPlanParser().parse("""
                ## TC-1: Created

                - **Automatable:** No

                **Steps**
                1. **Action:** a
                   **Expected:** b

                ## TC-2: Failed to create

                **Steps**
                1. **Action:** a
                   **Expected:** b
                """);
        var planFm = codec.parse(codec.render(Map.of("issue_key", "ERM-1"), "x")).metadata();

        String handoff = TestRailSyncAgentWorker.createdCases(planFm, 200L, cases, Map.of("TC-1", "C5"));

        assertThat(handoff).contains("\"caseId\":\"C5\"", "\"automatable\":false")
                .doesNotContain("TC-2").contains("\"areas\":[]");
        String unsynced = worker(new CapturingConnector(), null).execute(contextWith(testPlanWithOneCase()))
                .outputs().get(TestRailSyncAgentWorker.CREATED_CASES);
        assertThat(unsynced).contains("\"cases\":[]");
    }

    // -------------------------------------------------------------------------
    // Stub connector
    // -------------------------------------------------------------------------

    private static class CapturingConnector implements TestRailConnector {
        long capturedSectionId = -1L;
        long resolvedSectionId = 0L;
        Integer capturedDevTeamId = null;
        String capturedTitle = null;
        String capturedPreconds = null;
        Map<String, Object> capturedMetadata = null;
        Long capturedAttachmentCaseId = null;
        String capturedAttachmentFilename = null;
        byte[] capturedAttachmentBytes = null;
        boolean throwOnAttachment = false;
        final List<String> capturedAttachmentFilenames = new java.util.ArrayList<>();

        @Override
        public long addCase(long sectionId, String title, String steps, String expected) {
            return 0;
        }

        @Override
        public long addRun(String name, List<Long> caseIds) {
            return 0;
        }

        @Override
        public void addResults(long runId, Map<Long, Boolean> results, String comment) {
        }

        @Override
        public long addCaseWithMetadata(long sectionId, String title, String steps, String expected,
                                        Map<String, Object> metadata) {
            this.capturedSectionId = sectionId;
            this.capturedTitle = title;
            this.capturedMetadata = metadata;
            Object devTeam = metadata.get("custom_dev_team");
            this.capturedDevTeamId = devTeam instanceof Integer i ? i : null;
            Object preconds = metadata.get("custom_preconds");
            this.capturedPreconds = preconds instanceof String s ? s : null;
            return 999L;
        }

        @Override
        public long getOrCreateLabel(String title) {
            return 72L;
        }

        @Override
        public long addAttachmentToCase(long caseId, byte[] content, String filename) {
            if (throwOnAttachment) {
                throw new RuntimeException("TestRail attachment upload failed");
            }
            this.capturedAttachmentCaseId = caseId;
            this.capturedAttachmentFilename = filename;
            this.capturedAttachmentBytes = content;
            this.capturedAttachmentFilenames.add(filename);
            return 1L;
        }

        @Override
        public long resolveSectionByPath(String path) {
            return resolvedSectionId;
        }

        @Override
        public JsonNode getTestCaseDetails(String testCaseId) {
            return null;
        }

        @Override
        public List<org.folio.factory.connectors.testrail.TestRailFieldOption> getCaseTypes() {
            return List.of(
                    new org.folio.factory.connectors.testrail.TestRailFieldOption(1, "Automated", null),
                    new org.folio.factory.connectors.testrail.TestRailFieldOption(6, "Functional", null),
                    new org.folio.factory.connectors.testrail.TestRailFieldOption(7, "Other", null));
        }

        @Override
        public List<org.folio.factory.connectors.testrail.TestRailFieldOption> getPriorities() {
            return List.of(
                    new org.folio.factory.connectors.testrail.TestRailFieldOption(1, "Low", "Low"),
                    new org.folio.factory.connectors.testrail.TestRailFieldOption(2, "Medium", "Medium"),
                    new org.folio.factory.connectors.testrail.TestRailFieldOption(3, "High", "High"));
        }
    }
}
