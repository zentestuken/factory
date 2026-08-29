package org.folio.factory.app.hitl;

import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The carry-over set is derived from the restart step's declared inputs, so any flow whose
 * gate follows an AGENT step retries correctly without a control-plane change.
 */
class HitlRetryServiceTest {

    private static StepDescriptor step(List<String> inputs, List<String> optionalInputs) {
        return new StepDescriptor("gen", StepType.AGENT, "some-worker", null, null,
                inputs, optionalInputs, List.of("test_plan.md"), Map.of());
    }

    @Test
    void carriesOverRequiredAndOptionalInputs() {
        assertThat(HitlRetryService.carryOverArtifacts(
                step(List.of("scope_manifest.md", "context.md"), List.of("notes.md"))))
                .containsExactly("scope_manifest.md", "context.md", "notes.md");
    }

    @Test
    void skipsTheReservedTriggerPseudoInput() {
        assertThat(HitlRetryService.carryOverArtifacts(
                step(List.of("$trigger"), List.of("test_plan.md"))))
                .containsExactly("test_plan.md");
    }

    @Test
    void neverCopiesTheOldFeedbackArtifact() {
        // hitl_feedback.md is written fresh from this rejection's comments.
        assertThat(HitlRetryService.carryOverArtifacts(
                step(List.of("$trigger"), List.of("test_plan.md", "hitl_feedback.md"))))
                .containsExactly("test_plan.md");
    }

    @Test
    void deduplicatesAnArtifactDeclaredAsBothRequiredAndOptional() {
        assertThat(HitlRetryService.carryOverArtifacts(
                step(List.of("test_plan.md"), List.of("test_plan.md"))))
                .containsExactly("test_plan.md");
    }

    @Test
    void yieldsNothingWhenTheStepOnlyReadsTheTrigger() {
        assertThat(HitlRetryService.carryOverArtifacts(step(List.of("$trigger"), List.of())))
                .isEmpty();
    }

    @Test
    void matchesTheCypressTestcaseGenStep() {
        assertThat(HitlRetryService.carryOverArtifacts(
                step(List.of("$trigger"), List.of("test_plan.md", "hitl_feedback.md"))))
                .containsExactly("test_plan.md");
    }

    @Test
    void matchesATestFactoryStyleStep() {
        assertThat(HitlRetryService.carryOverArtifacts(
                step(List.of("scope_manifest.md"), List.of())))
                .containsExactly("scope_manifest.md");
    }
}
