package org.folio.factory.app.hitl;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Handles the REJECT_AND_RETRY decision at a HITL gate: rejects the current
 * execution and immediately creates a new one starting at the AGENT step that
 * produced the reviewed artifacts, pre-seeded with all upstream artifacts and
 * the reviewer's feedback as {@code hitl_feedback.md}.
 *
 * <p>Flow-agnostic: the artifacts to carry over are read from the restart step's declared
 * {@code inputs} and {@code optional_inputs} rather than a hardcoded list, so any flow whose
 * gate follows an AGENT step retries correctly without touching the control plane. Each is
 * copied at its latest version, so amendments made at the gate survive into the retry.</p>
 */
@Service
public class HitlRetryService {

    private static final Logger log = LoggerFactory.getLogger(HitlRetryService.class);

    /** Written by this service from the reviewer's comments, so never copied from the original. */
    public static final String FEEDBACK_ARTIFACT = "hitl_feedback.md";

    /** The AGENT step a rejected gate restarts from, and its index in the chain. */
    private record RestartPoint(int stepIndex, StepDescriptor step) {
    }

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
     * written as {@code hitl_feedback.md}, and the rejected artifacts are carried
     * over so the generator can revise rather than start from scratch.
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

        // Resolve before rejecting: a gate that cannot be restarted must not leave the
        // execution rejected with nothing to take its place.
        RestartPoint restart = restartPoint(review).orElseThrow(() -> new IllegalStateException(
                "Gate '" + gateId + "' cannot be re-generated: no AGENT step immediately precedes it"));
        int startStepIndex = restart.stepIndex();
        StepDescriptor startStep = restart.step();

        // Reject the original execution (transitions it to REJECTED).
        decisionService.decide(reviewId, HitlDecision.REJECT, reviewer, comments, null);

        PipelineExecution original = stateManager.get(originalExecutionId);

        PipelineExecution retry = stateManager.createExecution(
                original.getFlowId(), original.getFlowVersion(), original.getTriggerPayload(), null);
        retry.setCurrentStepIndex(startStepIndex);
        executionRepository.save(retry);

        UUID retryId = retry.getId();
        String createdBy = "hitl-retry:" + reviewer;

        for (String name : carryOverArtifacts(startStep)) {
            artifactStore.getLatest(originalExecutionId, name)
                    .ifPresent(a -> artifactStore.putMarkdown(retryId, name, a.getContent(), createdBy));
        }

        artifactStore.putMarkdown(retryId, FEEDBACK_ARTIFACT, comments, createdBy);

        auditLog.record(originalExecutionId, AuditEventType.HITL_RETRY, null, reviewer,
                Map.of("retryExecutionId", retryId.toString(), "gateId", gateId,
                        "startStepId", startStep.stepId()));

        log.info("[hitl-retry] Execution {} rejected at gate '{}'; retry {} starts at step {} ({})",
                originalExecutionId, gateId, retryId, startStepIndex, startStep.stepId());
        return retryId;
    }

    /**
     * Whether the "Reject and Re-generate" decision is available for this review — the same
     * question {@link #rejectAndRetry} answers, so the button and the service cannot disagree.
     * A pure read: it never throws, so a missing execution or an unregistered flow simply
     * means "no".
     */
    public boolean canRetry(HitlReview review) {
        return review.getStatus() == HitlReviewStatus.PENDING && restartPoint(review).isPresent();
    }

    /**
     * The AGENT step immediately before the reviewed gate, if there is one. This is the whole
     * of the flow-specific knowledge the retry path needs: any flow shaped {@code AGENT →
     * HITL_GATE} can re-generate, and no flow id or gate id is named anywhere.
     */
    private Optional<RestartPoint> restartPoint(HitlReview review) {
        PipelineExecution execution = executionRepository.findById(review.getExecutionId()).orElse(null);
        if (execution == null) {
            return Optional.empty();
        }
        FlowDescriptor descriptor = flowRegistry.find(execution.getFlowId()).orElse(null);
        if (descriptor == null) {
            return Optional.empty();
        }
        // An escalation review's step index points at the AGENT step that failed, not at a
        // gate. Requiring a HITL_GATE here keeps escalations out of the retry path without
        // naming the reserved escalation gate id.
        int gateIndex = review.getStepIndex();
        if (!descriptor.hasStep(gateIndex) || descriptor.step(gateIndex).type() != StepType.HITL_GATE) {
            return Optional.empty();
        }
        int startIndex = gateIndex - 1;
        if (!descriptor.hasStep(startIndex)) {
            return Optional.empty();
        }
        StepDescriptor start = descriptor.step(startIndex);
        return start.type() == StepType.AGENT
                ? Optional.of(new RestartPoint(startIndex, start))
                : Optional.empty();
    }

    /**
     * Everything the restart step declares it can read, minus the reserved {@code $trigger}
     * pseudo-input (carried on the execution itself) and {@code hitl_feedback.md} (written
     * fresh from this rejection's comments).
     */
    static List<String> carryOverArtifacts(StepDescriptor startStep) {
        return Stream.concat(startStep.inputs().stream(), startStep.optionalInputs().stream())
                .filter(name -> !name.startsWith("$"))
                .filter(name -> !FEEDBACK_ARTIFACT.equals(name))
                .distinct()
                .toList();
    }
}
