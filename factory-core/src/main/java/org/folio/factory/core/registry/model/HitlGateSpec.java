package org.folio.factory.core.registry.model;

import java.util.List;

/**
 * @param referencePrompts prompt ids whose templates define automated checks applied before
 *                         this gate — a reviewer agent's checklist, say. Surfaced at the gate
 *                         as links to {@code /prompts/{id}} so a human can read the criteria
 *                         their approval is resting on without going hunting in the repo.
 */
public record HitlGateSpec(
        String gateId,
        String title,
        String reviewInstructions,
        List<String> reviewedArtifacts,
        List<String> referencePrompts) {

    public HitlGateSpec {
        reviewedArtifacts = reviewedArtifacts == null ? List.of() : List.copyOf(reviewedArtifacts);
        referencePrompts = referencePrompts == null ? List.of() : List.copyOf(referencePrompts);
    }
}
