package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.llm.StubChatModel;
import org.folio.factory.connectors.UnconfiguredConnectors;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioAnalysisAgentWorkerTest {

    private static final String SCENARIO_LIST_RESPONSE = """
            # Scenario Analysis: ERM-5

            ## Proposed scenarios

            ### Happy path
            - **S-01** | Create agreement with valid name | Type: Functional | Steps: ~5 | Journey: No | Tier: critical
              _Covers: AC-1. Tests that agreement is saved successfully._
            """;

    private ScenarioAnalysisAgentWorker worker(StubChatModel model) {
        return new ScenarioAnalysisAgentWorker(ChatClient.create(model), new UnconfiguredConnectors.TestRail());
    }

    private AgentContext contextWith(String scopeManifest, String context) {
        return new AgentContext(UUID.randomUUID(), "scenario-analysis",
                Map.of(
                        "scope_manifest.md", new ArtifactContent("scope_manifest.md", 1, "text/markdown", scopeManifest),
                        "context.md", new ArtifactContent("context.md", 1, "text/markdown", context)),
                null, Map.of(), List.of("scenario_list.md"));
    }

    @Test
    void returnsLlmResponseAsScenarioListArtifact() {
        StubChatModel model = new StubChatModel().enqueue(SCENARIO_LIST_RESPONSE);

        AgentResult result = worker(model).execute(contextWith("scope: ERM-5", "# Context"));

        assertThat(result.outputs()).containsKey("scenario_list.md");
        assertThat(result.outputs().get("scenario_list.md")).isEqualTo(SCENARIO_LIST_RESPONSE);
    }

    @Test
    void passesScopeManifestAndContextToPrompt() {
        StubChatModel model = new StubChatModel().enqueue(SCENARIO_LIST_RESPONSE);

        worker(model).execute(contextWith("manifest-content", "context-content"));

        String prompt = model.receivedPrompts().getFirst().getContents();
        assertThat(prompt).contains("manifest-content");
        assertThat(prompt).contains("context-content");
    }

    @Test
    void passesAvailableSectionsToPrompt() {
        StubChatModel model = new StubChatModel().enqueue(SCENARIO_LIST_RESPONSE);
        List<String> sections = List.of("Inventory", "Inventory \u203a Search", "MARC \u203a MARC authority");
        TestRailConnector connector = new TestRailConnector() {
            @Override public long addCase(long s, String t, String steps, String exp) { return 0; }
            @Override public long addRun(String n, List<Long> ids) { return 0; }
            @Override public void addResults(long r, Map<Long, Boolean> res, String c) {}

            @Override
            public JsonNode getTestCaseDetails(String testCaseId) {
                return null;
            }

            @Override public List<String> listSectionPaths() { return sections; }
        };

        new ScenarioAnalysisAgentWorker(ChatClient.create(model), connector)
                .execute(contextWith("manifest", "context"));

        String prompt = model.receivedPrompts().getFirst().getContents();
        assertThat(prompt).contains("Inventory \u203a Search");
        assertThat(prompt).contains("MARC \u203a MARC authority");
    }

    @Test
    void usesFallbackMessageWhenConnectorHasNoSections() {
        StubChatModel model = new StubChatModel().enqueue(SCENARIO_LIST_RESPONSE);

        worker(model).execute(contextWith("manifest", "context")); // UnconfiguredConnectors returns List.of()

        String prompt = model.receivedPrompts().getFirst().getContents();
        assertThat(prompt).contains("not configured");
    }

    @Test
    void failsWhenScopeManifestMissing() {
        StubChatModel model = new StubChatModel();
        AgentContext ctx = new AgentContext(UUID.randomUUID(), "scenario-analysis",
                Map.of("context.md", new ArtifactContent("context.md", 1, "text/markdown", "x")),
                null, Map.of(), List.of("scenario_list.md"));

        assertThatThrownBy(() -> worker(model).execute(ctx))
                .isInstanceOf(NoSuchElementException.class);
    }
}
