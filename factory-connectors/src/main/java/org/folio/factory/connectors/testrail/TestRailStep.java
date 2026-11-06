package org.folio.factory.connectors.testrail;

/**
 * One step of an existing TestRail case.
 *
 * <p>TestRail's step storage is template-dependent: most templates use the structured
 * {@code custom_steps_separated} array, but the "Test Case (Text)" template stores a single
 * {@code custom_steps} blob with no per-step expected result. A blob is read as one step with
 * an empty {@link #expected()}.</p>
 */
public record TestRailStep(String action, String expected) {
    public TestRailStep {
        action = action == null ? "" : action.strip();
        expected = expected == null ? "" : expected.strip();
    }

    public boolean isBlank() {
        return action.isBlank() && expected.isBlank();
    }
}
