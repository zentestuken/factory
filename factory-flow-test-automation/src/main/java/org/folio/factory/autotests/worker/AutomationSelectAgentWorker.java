package org.folio.factory.autotests.worker;

import org.folio.factory.autotests.TestAutomationFlowProperties;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.ArtifactContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which TestRail cases this run automates, and fetches them. Deterministic - no model call.
 *
 * <p>Case ids come from the trigger ({@code testCaseIds} / {@code testCaseId}, an automation-only
 * run started by partial-run) or else from {@code testrail_cases.json} (the cases the sync step
 * just created). A run's {@code "automate": false} - or {@code FACTORY_CYPRESS_AUTOMATION_ENABLED=false}
 * when the run says nothing - selects nothing. Non-automatable cases and cases over the cap are
 * listed as skipped, never silently dropped.</p>
 */
public class AutomationSelectAgentWorker implements AgentWorker {

    public static final String ID = "cypress-automation-select";
    public static final String HANDOFF = "testrail_cases.json";
    public static final String SELECTION = "automation_cases.json";
    private static final JsonMapper JSON = new JsonMapper();
    private static final Logger log = LoggerFactory.getLogger(AutomationSelectAgentWorker.class);

    private final TestRailConnector testRail;
    private final TestAutomationFlowProperties properties;
    private final DevTeamNames devTeams;

    public AutomationSelectAgentWorker(TestRailConnector testRail, TestAutomationFlowProperties properties) {
        this(testRail, properties, DevTeamNames.load());
    }

    AutomationSelectAgentWorker(TestRailConnector testRail, TestAutomationFlowProperties properties,
                                DevTeamNames devTeams) {
        this.testRail = testRail;
        this.properties = properties;
        this.devTeams = devTeams;
    }

    @Override
    public String id() {
        return ID;
    }

    private record Candidate(String caseId, boolean automatable, String automationNotes) {}

    @Override
    public AgentResult execute(AgentContext context) {
        JsonNode trigger = context.triggerPayload() == null ? JSON.createObjectNode() : context.triggerPayload();
        ArtifactContent handoffArtifact = context.inputs().get(HANDOFF);
        JsonNode handoff = handoffArtifact == null ? JSON.createObjectNode() : JSON.readTree(handoffArtifact.content());

        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("issueKey", trigger.path("issueKey").asString(handoff.path("issueKey").asString("")));
        selection.put("areas", areas(trigger.has("areas") ? trigger.path("areas") : handoff.path("areas")));
        List<Map<String, Object>> selected = new ArrayList<>();
        List<Map<String, String>> skipped = new ArrayList<>();
        selection.put("cases", selected);
        selection.put("skipped", skipped);

        boolean enabled = trigger.has("automate") ? trigger.path("automate").asBoolean(true) : properties.enabled();
        if (!enabled) {
            log.info("[automation] Disabled for this run - nothing will be generated");
            selection.put("note", "Automation disabled for this run");
            return AgentResult.of(SELECTION, JSON.writeValueAsString(selection));
        }

        List<Candidate> candidates = candidates(trigger, handoff, handoffArtifact != null);
        for (Candidate c : candidates) {
            if (!c.automatable()) {
                skipped.add(Map.of("caseId", c.caseId(), "reason", "marked Automatable: No"));
            } else if (selected.size() >= properties.maxCases()) {
                skipped.add(Map.of("caseId", c.caseId(), "reason",
                        "over the cap of " + properties.maxCases() + " (FACTORY_CYPRESS_AUTOMATION_MAX_CASES)"));
            } else {
                JsonNode details = fetch(c.caseId());
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("caseId", c.caseId());
                entry.put("title", details.path("title").asString(""));
                entry.put("automationNotes", c.automationNotes());
                entry.put("team", team(handoff, details));
                entry.put("details", details);
                selected.add(entry);
            }
        }
        if (selected.isEmpty()) {
            selection.put("note", "No automatable cases");
        }
        log.info("[automation] Automating {} of {} case(s): {}{}", selected.size(), candidates.size(),
                selected.stream().map(c -> c.get("caseId")).toList(),
                skipped.isEmpty() ? "" : "; skipped " + skipped.stream()
                        .map(s -> s.get("caseId") + " (" + s.get("reason") + ")").toList());
        return AgentResult.of(SELECTION, JSON.writeValueAsString(selection));
    }

    private List<Candidate> candidates(JsonNode trigger, JsonNode handoff, boolean hasHandoff) {
        List<Candidate> fromTrigger = new ArrayList<>();
        trigger.path("testCaseIds").forEach(id -> fromTrigger.add(new Candidate(normalise(id.asString("")), true, "")));
        if (trigger.hasNonNull("testCaseId")) {
            fromTrigger.add(new Candidate(normalise(trigger.path("testCaseId").asString("")), true, ""));
        }
        if (!fromTrigger.isEmpty()) {
            return fromTrigger;
        }
        if (!hasHandoff) {
            throw new AgentExecutionException("Nothing to automate: give testCaseIds in the trigger payload "
                    + "(partial-run at '" + ID + "'), or run this step after testrail-sync");
        }
        List<Candidate> fromHandoff = new ArrayList<>();
        handoff.path("cases").forEach(c -> fromHandoff.add(new Candidate(c.path("caseId").asString(""),
                c.path("automatable").asBoolean(true), c.path("automationNotes").asString(""))));
        return fromHandoff;
    }

    private JsonNode fetch(String caseId) {
        try {
            JsonNode details = testRail.getTestCaseDetails(caseId.substring(1));
            if (details == null || !details.has("id")) {
                throw new AgentExecutionException("TestRail has no case " + caseId);
            }
            return details;
        } catch (AgentExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AgentExecutionException("Cannot fetch TestRail case " + caseId + ": " + e.getMessage(), e);
        }
    }

    /**
     * The case's dev team, the same one its TestRail case carries: the story's team from the
     * hand-off, or - for an automation-only run - the case's Dev Team option id mapped back to a name.
     */
    private String team(JsonNode handoff, JsonNode details) {
        String fromStory = handoff.path("team").asString("");
        if (!fromStory.isBlank()) return fromStory;
        JsonNode id = details.path("custom_dev_team");
        return id.isNumber() ? devTeams.nameFor(id.asInt()).orElse("") : "";
    }

    /** {@code "C123"}, {@code "c123"} and {@code "123"} all become {@code "C123"}. */
    static String normalise(String id) {
        String digits = id.strip().replaceFirst("^[Cc]", "");
        if (!digits.matches("\\d+")) {
            throw new AgentExecutionException("Not a TestRail case id: '" + id + "'");
        }
        return "C" + digits;
    }

    private static List<String> areas(JsonNode node) {
        List<String> areas = new ArrayList<>();
        node.forEach(a -> areas.add(a.asString("")));
        return areas;
    }
}
