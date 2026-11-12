package org.folio.factory.autotests.worker;

import org.folio.factory.autotests.TestAutomationFlowProperties;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.ArtifactContent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationSelectAgentWorkerTest {

    private static final JsonMapper JSON = new JsonMapper();

    static class FakeTestRail implements TestRailConnector {
        final List<String> fetched = new ArrayList<>();

        @Override public JsonNode getTestCaseDetails(String id) {
            fetched.add(id);
            if (id.equals("404")) throw new IllegalStateException("400 Bad Request: Field :case_id is not a valid test case");
            return JSON.readTree("{\"id\":" + id + ",\"title\":\"Case " + id + "\"}");
        }
        @Override public long addCase(long s, String t, String st, String e) { return 0; }
        @Override public long addRun(String n, List<Long> ids) { return 0; }
        @Override public void addResults(long r, Map<Long, Boolean> res, String c) { }
    }

    private static TestAutomationFlowProperties props(Boolean enabled, Integer maxCases) {
        return new TestAutomationFlowProperties(null, null, null, null, null, null, null,
                enabled, maxCases, null, null, null, null, null, null);
    }

    private static final String HANDOFF = """
            {"issueKey":"UIU-1","team":"Dresden","areas":["eureka"],"cases":[
              {"caseId":"C1","automatable":true,"automationNotes":"reuse usersCard.js"},
              {"caseId":"C2","automatable":false,"automationNotes":""},
              {"caseId":"C3","automatable":true,"automationNotes":""},
              {"caseId":"C4","automatable":true,"automationNotes":""}]}
            """;

    private static AgentContext context(String trigger, String handoff) {
        Map<String, ArtifactContent> inputs = new HashMap<>();
        if (handoff != null) {
            inputs.put(AutomationSelectAgentWorker.HANDOFF,
                    new ArtifactContent(AutomationSelectAgentWorker.HANDOFF, 1, "application/json", handoff));
        }
        return new AgentContext(UUID.randomUUID(), "automation-select", inputs, JSON.readTree(trigger),
                Map.of(), List.of(AutomationSelectAgentWorker.SELECTION));
    }

    private static JsonNode select(TestRailConnector testRail, TestAutomationFlowProperties props,
                                   String trigger, String handoff) {
        String out = new AutomationSelectAgentWorker(testRail, props).execute(context(trigger, handoff))
                .outputs().get(AutomationSelectAgentWorker.SELECTION);
        return JSON.readTree(out);
    }

    @Test
    void afterSyncTakesAutomatableCasesUpToTheCapAndListsTheRestAsSkipped() {
        FakeTestRail testRail = new FakeTestRail();

        JsonNode selection = select(testRail, props(null, null), "{\"issueKey\":\"UIU-1\"}", HANDOFF);

        assertThat(testRail.fetched).containsExactly("1", "3");
        assertThat(selection.path("cases").size()).isEqualTo(2);
        assertThat(selection.path("cases").get(0).path("automationNotes").asString("")).isEqualTo("reuse usersCard.js");
        assertThat(selection.path("cases").get(0).path("details").path("title").asString("")).isEqualTo("Case 1");
        assertThat(selection.path("areas").get(0).asString("")).isEqualTo("eureka");
        assertThat(selection.path("skipped").toString()).contains("C2", "Automatable: No", "C4", "over the cap of 2");
    }

    @Test
    void triggerIdsWinOverTheHandoffAndAreNormalised() {
        FakeTestRail testRail = new FakeTestRail();

        JsonNode selection = select(testRail, props(null, 5),
                "{\"testCaseIds\":[\"c10\",\"11\"],\"testCaseId\":\"C12\"}", HANDOFF);

        assertThat(testRail.fetched).containsExactly("10", "11", "12");
        assertThat(selection.path("cases").get(0).path("caseId").asString("")).isEqualTo("C10");
    }

    @Test
    void automateFalseInThePayloadSelectsNothingEvenWhenEnabledByDefault() {
        FakeTestRail testRail = new FakeTestRail();

        JsonNode selection = select(testRail, props(true, null), "{\"issueKey\":\"UIU-1\",\"automate\":false}", HANDOFF);

        assertThat(testRail.fetched).isEmpty();
        assertThat(selection.path("cases").size()).isZero();
        assertThat(selection.path("note").asString("")).isEqualTo("Automation disabled for this run");
    }

    @Test
    void theEnvDefaultAppliesWhenThePayloadIsSilentAndThePayloadCanOverrideIt() {
        assertThat(select(new FakeTestRail(), props(false, null), "{}", HANDOFF).path("note").asString(""))
                .isEqualTo("Automation disabled for this run");
        assertThat(select(new FakeTestRail(), props(false, null), "{\"automate\":true}", HANDOFF)
                .path("cases").size()).isEqualTo(2);
    }

    @Test
    void failsClearlyWithNothingToAutomateOrAnUnknownCase() {
        var worker = new AutomationSelectAgentWorker(new FakeTestRail(), props(null, null));

        assertThatThrownBy(() -> worker.execute(context("{\"issueKey\":\"UIU-1\"}", null)))
                .isInstanceOf(AgentExecutionException.class).hasMessageContaining("testCaseIds");
        assertThatThrownBy(() -> worker.execute(context("{\"testCaseIds\":[\"C404\"]}", null)))
                .isInstanceOf(AgentExecutionException.class).hasMessageContaining("C404");
        assertThatThrownBy(() -> worker.execute(context("{\"testCaseIds\":[\"UIU-1\"]}", null)))
                .isInstanceOf(AgentExecutionException.class).hasMessageContaining("Not a TestRail case id");
    }

    @Test
    void eachCaseCarriesTheTeamItsTestRailCaseHas() {
        JsonNode fromStory = select(new FakeTestRail(), props(null, null), "{}", HANDOFF);
        assertThat(fromStory.path("cases").get(0).path("team").asString("")).isEqualTo("Dresden");

        FakeTestRail withTeamId = new FakeTestRail() {
            @Override public JsonNode getTestCaseDetails(String id) {
                return JSON.readTree("{\"id\":" + id + ",\"title\":\"x\",\"custom_dev_team\":24}");
            }
        };
        DevTeamNames table = DevTeamNames.parse("""
                | Jira name | Jira ID | TestRail name | TestRail ID | Superseded by |
                |---|---|---|---|---|
                | Dresden | 11080 | Dresden | 24 | |
                """);
        String out = new AutomationSelectAgentWorker(withTeamId, props(null, null), table)
                .execute(context("{\"testCaseIds\":[\"C5\"]}", null)).outputs().get(AutomationSelectAgentWorker.SELECTION);
        assertThat(JSON.readTree(out).path("cases").get(0).path("team").asString("")).isEqualTo("Dresden");
    }
}
