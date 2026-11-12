package org.folio.factory.autotests.workspace;

/**
 * What the TAF's own pre-commit hook would do, run explicitly before each case's commit (the hook
 * itself is disabled for factory commits). Remaining problems are reported, not fixed - fixing them
 * with the model is a later step.
 */
public interface PreCommitChecks {

    /**
     * Readies the workspace once per run. Never throws: when the checks can't run, returns why
     * (and {@link #run} is then skipped); otherwise returns blank.
     */
    String prepare(AutomationWorkspace workspace);

    /** Runs on the current case's changes; returns the problems that remain, blank when clean. */
    String run(AutomationWorkspace workspace);

    /** The TAF's {@code lint-staged} config: prettier --write, then stripes-lint --fix, on staged *.js. */
    final class LintStaged implements PreCommitChecks {

        @Override
        public String prepare(AutomationWorkspace workspace) {
            try {
                workspace.ensureDependencies();
                return "";
            } catch (RuntimeException e) {
                return "dependencies could not be installed: " + e.getMessage();
            }
        }

        @Override
        public String run(AutomationWorkspace workspace) {
            return workspace.runLintStaged();
        }
    }
}
