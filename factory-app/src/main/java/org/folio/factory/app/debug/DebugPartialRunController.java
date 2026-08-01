package org.folio.factory.app.debug;

import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Debug endpoint for partial-flow testing. Creates an execution pre-seeded with
 * fixture artifacts and starts it at a non-zero step index so only the target
 * step(s) run — skipping expensive LLM steps that precede the one under test.
 *
 * <p>Only active when {@code FACTORY_DEBUG_ENABLED=true}. The endpoint does not
 * exist at all when the property is absent or false — no auth guard needed.</p>
 *
 * <p>Usage: start from step 8 (testrail-sync) to test TestRail wiring, or start
 * from step 7 (gate-1-review) to exercise the amendment → sync path.</p>
 */
@RestController
@RequestMapping("/api/debug")
@ConditionalOnProperty(name = "factory.debug.enabled", havingValue = "true")
public class DebugPartialRunController {

    private final StateManager stateManager;
    private final PipelineExecutionRepository executionRepository;
    private final ArtifactStore artifactStore;
    private final FlowRegistry flowRegistry;
    private final JsonMapper jsonMapper;

    public DebugPartialRunController(StateManager stateManager,
                                     PipelineExecutionRepository executionRepository,
                                     ArtifactStore artifactStore,
                                     FlowRegistry flowRegistry,
                                     JsonMapper jsonMapper) {
        this.stateManager = stateManager;
        this.executionRepository = executionRepository;
        this.artifactStore = artifactStore;
        this.flowRegistry = flowRegistry;
        this.jsonMapper = jsonMapper;
    }

    public record PartialRunRequest(
            String flowId,
            int startStepIndex,
            JsonNode triggerPayload,
            Map<String, String> artifacts) {
    }

    public record PartialRunResponse(
            UUID executionId,
            String flowId,
            int startStepIndex,
            String startStepId,
            List<String> seededArtifacts) {
    }

    /**
     * Creates an execution at {@code startStepIndex} with pre-seeded artifacts.
     * The poller picks it up within its next poll cycle (~2 s) and the engine
     * advances from the target step — not from step 0.
     *
     * <p>All operations run in a single transaction so the poller cannot claim
     * the execution before the artifacts are committed.</p>
     */
    @PostMapping("/partial-run")
    @Transactional
    public ResponseEntity<PartialRunResponse> start(@RequestBody PartialRunRequest req) {
        FlowDescriptor flow = flowRegistry.require(req.flowId());

        if (!flow.hasStep(req.startStepIndex())) {
            throw new IllegalArgumentException(
                    "startStepIndex " + req.startStepIndex() + " is out of range for flow '"
                            + req.flowId() + "' (" + flow.agentChain().size() + " steps, indices 0–"
                            + (flow.agentChain().size() - 1) + ")");
        }

        StepDescriptor startStep = flow.step(req.startStepIndex());
        validateArtifactsForStep(startStep, req.artifacts());

        String payloadJson = req.triggerPayload() == null ? null
                : jsonMapper.writeValueAsString(req.triggerPayload());

        var execution = stateManager.createExecution(req.flowId(), flow.version(), payloadJson, null);

        // Advance cursor to target step within the same transaction.
        // The poller uses SELECT FOR UPDATE SKIP LOCKED in a separate transaction
        // and cannot see this row until we commit.
        execution.setCurrentStepIndex(req.startStepIndex());
        executionRepository.save(execution);

        List<String> seeded = new ArrayList<>();
        if (req.artifacts() != null) {
            for (var entry : req.artifacts().entrySet()) {
                artifactStore.putMarkdown(execution.getId(), entry.getKey(), entry.getValue(), "debug:seed");
                seeded.add(entry.getKey());
            }
        }

        return ResponseEntity.accepted()
                .body(new PartialRunResponse(execution.getId(), req.flowId(),
                        req.startStepIndex(), startStep.stepId(), seeded));
    }

    private void validateArtifactsForStep(StepDescriptor step, Map<String, String> artifacts) {
        for (String input : step.inputs()) {
            if (StepDescriptor.TRIGGER_INPUT.equals(input)) continue;
            if (artifacts == null || !artifacts.containsKey(input)) {
                throw new IllegalArgumentException(
                        "Artifact '" + input + "' is required by start step '" + step.stepId()
                                + "' but was not provided in the artifacts map");
            }
        }
    }
}
