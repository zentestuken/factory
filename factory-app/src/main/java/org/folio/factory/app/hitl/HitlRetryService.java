package org.folio.factory.app.hitl;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Handles the REJECT_AND_RETRY decision at a HITL gate: rejects the current
 * execution and immediately creates a new one starting at the AGENT step that
 * produced the reviewed artifacts, pre-seeded with all upstream artifacts and
 * the reviewer's feedback as {@code hitl_feedback.md}.
 *
 * <p>The rejected test cases are copied as {@code previous_test_plan.md} so the
 * generator can see what was rejected alongside the feedback.</p>
 */
@Service
public class HitlRetryService {

    private static final Logger log = LoggerFactory.getLogger(HitlRetryService.class);

    private static final List<String> CARRY_OVER_ARTIFACTS = List.of(
            "scope_manifest.md", "context.md", "fragment_library.md",
            "examples.md", "scenario_list.md", "existing_coverage.md");

    private final HitlDecisionService decisionService;
    private final HitlReviewRepository reviewRepository;
    private final StateManager stateManager;
    private final PipelineExecutionRepository executionRepository;
    private final ArtifactStore artifactStore;
    private final FlowRegistry flowRegistry;
    private final AuditLog auditLog;

    public HitlRetryService(HitlDecisionService decisionService,
                            HitlReviewRepository reviewRepository,
                            StateManager stateManager,
                            PipelineExecutionRepository executionRepository,
                            ArtifactStore artifactStore,
                            FlowRegistry flowRegistry,
                            AuditLog auditLog) {
        this.decisionService = decisionService;
        this.reviewRepository = reviewRepository;
        this.stateManager = stateManager;
        this.executionRepository = executionRepository;
        this.artifactStore = artifactStore;
        this.flowRegistry = flowRegistry;
        this.auditLog = auditLog;
    }

    /**
     * Rejects the current execution and starts a new one from the AGENT step
     * immediately before the given gate. The reviewer's {@code comments} are
     * written as {@code hitl_feedback.md}; the rejected {@code test_plan.md}
     * (if present) is copied as {@code previous_test_plan.md}.
     *
     * @return the new execution's ID
     */
    @Transactional
    public UUID rejectAndRetry(UUID reviewId, String reviewer, String comments) {
        if (comments == null || comments.isBlank()) {
            throw new IllegalArgumentException("Feedback is required when using Reject and Re-generate");
        }

        HitlReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new NoSuchElementException("No HITL review " + reviewId));
        UUID originalExecutionId = review.getExecutionId();
        String gateId = review.getGateId();
        int gateStepIndex = review.getStepIndex();

        // Reject the original execution (transitions it to REJECTED).
        decisionService.decide(reviewId, HitlDecision.REJECT, reviewer, comments, null);

        PipelineExecution original = stateManager.get(originalExecutionId);
        FlowDescriptor descriptor = flowRegistry.require(original.getFlowId());

        int startStepIndex = gateStepIndex - 1;
        if (startStepIndex < 0) {
            throw new IllegalStateException("Gate '" + gateId + "' is at step 0 — no preceding step to retry from");
        }
        StepDescriptor startStep = descriptor.step(startStepIndex);
        if (startStep.type() != StepType.AGENT) {
            throw new IllegalStateException(
                    "Step " + startStepIndex + " preceding gate '" + gateId + "' is not an AGENT step");
        }

        PipelineExecution retry = stateManager.createExecution(
                original.getFlowId(), original.getFlowVersion(), original.getTriggerPayload(), null);
        retry.setCurrentStepIndex(startStepIndex);
        executionRepository.save(retry);

        UUID retryId = retry.getId();
        String createdBy = "hitl-retry:" + reviewer;

        for (String name : CARRY_OVER_ARTIFACTS) {
            artifactStore.getLatest(originalExecutionId, name)
                    .ifPresent(a -> artifactStore.putMarkdown(retryId, name, a.getContent(), createdBy));
        }
        artifactStore.getLatest(originalExecutionId, "test_plan.md")
                .ifPresent(a -> artifactStore.putMarkdown(retryId, "previous_test_plan.md", a.getContent(), createdBy));

        artifactStore.putMarkdown(retryId, "hitl_feedback.md", comments, createdBy);

        auditLog.record(originalExecutionId, AuditEventType.HITL_RETRY, null, reviewer,
                Map.of("retryExecutionId", retryId.toString(), "gateId", gateId,
                        "startStepId", startStep.stepId()));

        log.info("[hitl-retry] Execution {} rejected at gate '{}'; retry {} starts at step {} ({})",
                originalExecutionId, gateId, retryId, startStepIndex, startStep.stepId());
        return retryId;
    }
}
