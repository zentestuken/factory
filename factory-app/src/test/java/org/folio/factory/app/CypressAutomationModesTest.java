package org.folio.factory.app;

import org.folio.factory.app.debug.DebugPartialRunController;
import org.folio.factory.app.debug.DebugPartialRunController.PartialRunRequest;
import org.folio.factory.autotests.cli.CodingAgentCli;
import org.folio.factory.autotests.worker.CypressTestFrameworkExplorerAgentWorker;
import org.folio.factory.autotests.workspace.AutomationWorkspace;
import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.connectors.testrail.TestRailFieldOption;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three ways to run cypress-factory, through the real descriptor and engine: from the sync
 * step into automation (the hand-off), cases only ({@code "automate": false}), and automation only
 * (partial-run at automation-select). TestRail, the claude CLI, the explorer's model call and the
 * git workspace are faked; case generation is covered by the cypress module's own tests.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.poll-interval-ms=250",
        "factory.debug.enabled=true"
})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class CypressAutomationModesTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean(extraInterfaces = ConnectorHealth.class) TestRailConnector testRail;
    @MockitoBean CodingAgentCli cli;
    @MockitoBean AutomationWorkspace workspace;
    @MockitoSpyBean CypressTestFrameworkExplorerAgentWorker explorer;

    @Autowired DebugPartialRunController partialRun;
    @Autowired StateManager stateManager;
    @Autowired ArtifactStore artifactStore;
    @Autowired HitlReviewRepository reviews;
    @Autowired HitlDecisionService decisionService;

    @TempDir
    Path workDir;

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicLong nextCaseId = new AtomicLong(1001);

    private static final String PLAN = """
            ---
            issue_key: "UIU-1"
            team: ""
            ecs_story: "non-ecs"
            testrail_section: "200"
            areas: ["eureka"]
            case_count: 2
            ---

            # Test Cases

            ## TC001: Automatable case

            - **Test Group:** extended
            - **Automatable:** Yes
            - **ECS:** No

            **Steps**
            1. **Action:** open the user
               **Expected:** the accordion is shown

            ## TC002: Manual-only case

            - **Test Group:** extended
            - **Automatable:** No
            - **ECS:** No

            **Steps**
            1. **Action:** look at it
               **Expected:** it looks right
            """;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void fakes() {
        when(testRail.getCaseTypes()).thenReturn(List.of(new TestRailFieldOption(1, "Functional", null)));
        when(testRail.getPriorities()).thenReturn(List.of(new TestRailFieldOption(2, "Medium", null)));
        when(testRail.getOrCreateLabel(anyString())).thenReturn(72L);
        when(testRail.addCaseWithMetadata(anyLong(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> nextCaseId.getAndIncrement());
        when(testRail.getTestCaseDetails(anyString()))
                .thenAnswer(inv -> json.readTree("{\"id\":" + inv.getArgument(0) + ",\"title\":\"Case "
                        + inv.getArgument(0) + "\"}"));
        when(workspace.root()).thenReturn(workDir);
        when(workspace.onBranch(anyString(), any()))
                .thenAnswer(inv -> ((Function<Path, Object>) inv.getArgument(1)).apply(workDir));
        when(workspace.branchChanges()).thenReturn("cypress/e2e/users/new.cy.js | 40 ++++");
        when(workspace.runLintStaged()).thenReturn("");
        doReturn(json.readTree("{\"similar_tests\":[],\"support_files\":[]}")).when(explorer).explore(any(), any());
        when(cli.run(anyString(), any(), any())).thenAnswer(inv -> ((List<String>) inv.getArgument(2))
                .contains("Bash(git status:*)")
                ? "Reviewed.\n```json\n{\"verdict\":\"NEEDS_RERUN\",\"ran\":false,\"spec\":\"cypress/e2e/users/new.cy.js\",\"findings\":[]}\n```"
                : "Wrote cypress/e2e/users/new.cy.js");
    }

    private UUID start(String stepId, String trigger, Map<String, String> artifacts) {
        PartialRunRequest request = new PartialRunRequest("cypress-factory", null, stepId,
                json.readTree(trigger), artifacts);
        return partialRun.start(request).getBody().executionId();
    }

    @Test
    void afterTheSyncTheAutomatableCaseIsAutomatedAndTheOtherIsListedAsSkipped() {
        UUID id = start("testrail-sync", "{\"issueKey\":\"UIU-1\"}", Map.of("test_plan.md", PLAN));

        approveGate2(id);

        String handoff = artifactStore.getLatest(id, "testrail_cases.json").orElseThrow().getContent();
        assertThat(handoff).contains("\"caseId\":\"C1001\"", "\"caseId\":\"C1002\"", "\"areas\":[\"eureka\"]");
        String report = artifactStore.getLatest(id, "automation_report.md").orElseThrow().getContent();
        assertThat(report).contains("## C1001", "cypress/e2e/users/new.cy.js", "NEEDS_RERUN",
                "C1002 - marked Automatable: No");
        verify(explorer).explore(any(), any());
        verify(workspace).onBranch(org.mockito.ArgumentMatchers.eq("factory/UIU-1"), any());
    }

    @Test
    void automateFalseStopsAfterTheSyncWithNothingGenerated() {
        UUID id = start("testrail-sync", "{\"issueKey\":\"UIU-1\",\"automate\":false}", Map.of("test_plan.md", PLAN));

        approveGate2(id);

        assertThat(artifactStore.getLatest(id, "automation_report.md").orElseThrow().getContent())
                .contains("Automation disabled for this run");
        verify(cli, never()).run(anyString(), any(), any());
        verify(workspace, never()).onBranch(anyString(), any());
    }

    @Test
    void automationOnlyRunsOnTheGivenCasesWithTheirAreas() {
        UUID id = start("automation-select", "{\"testCaseIds\":[\"C77\"],\"areas\":[\"inventory\"]}", Map.of());

        approveGate2(id);

        assertThat(artifactStore.getLatest(id, "automation_report.md").orElseThrow().getContent())
                .contains("`factory/C77`", "## C77 - Case 77");
        verify(explorer).explore(any(), org.mockito.ArgumentMatchers.eq(List.of("inventory")));
    }

    private void approveGate2(UUID executionId) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                        .anyMatch(r -> r.getGateId().equals("gate-2-autotests")
                                && r.getStatus() == HitlReviewStatus.PENDING)).isTrue());
        var review = reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                .filter(r -> r.getGateId().equals("gate-2-autotests") && r.getStatus() == HitlReviewStatus.PENDING)
                .findFirst().orElseThrow();
        decisionService.decide(review.getId(), HitlDecision.APPROVE, "aqa", null, null);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.COMPLETED));
    }
}
