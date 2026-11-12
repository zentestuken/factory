package org.folio.factory.autotests;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.test-automation-factory")
public record TestAutomationFlowProperties(
        String targetRepo,
        String baseBranch,
        String folioAiRepo,
        Long testrailSectionId,
        Long testrailCaseTypeId,   // set FACTORY_CYPRESS_TESTRAIL_CASE_TYPE_ID (e.g. 1=Functional in foliotest)
        Long testrailPriorityId,   // set FACTORY_CYPRESS_TESTRAIL_PRIORITY_ID; default 3 = Medium
        String testrailLabel,      // set FACTORY_CYPRESS_TESTRAIL_LABEL; default "cypressFlow-delete"
        Boolean enabled,           // FACTORY_CYPRESS_AUTOMATION_ENABLED; a run's "automate" payload field wins
        Integer maxCases,          // FACTORY_CYPRESS_AUTOMATION_MAX_CASES; cost cap per run
        Boolean runSpecs,          // FACTORY_CYPRESS_AUTOMATION_RUN_SPECS; needs node_modules + a FOLIO env
        Integer cliTimeoutSeconds, // FACTORY_CYPRESS_AUTOMATION_TIMEOUT_SECONDS; per claude CLI session
        String workspaceDir,       // FACTORY_CYPRESS_AUTOMATION_WORKSPACE; the clone automation writes to
        String runEnvironmentsFile, // FACTORY_CYPRESS_AUTOMATION_RUN_ENVS; replaces the bundled ecs/non-ecs envs
        String cliModel,           // FACTORY_CYPRESS_AUTOMATION_CLI_MODEL; claude CLI --model
        String cliEffort) {        // FACTORY_CYPRESS_AUTOMATION_CLI_EFFORT; claude CLI --effort

    static final String DEFAULT_TESTRAIL_LABEL = "cypressFlow-delete";

    public TestAutomationFlowProperties {
        baseBranch = baseBranch == null || baseBranch.isBlank() ? "master" : baseBranch;
        targetRepo = targetRepo == null ? "" : targetRepo;
        folioAiRepo = folioAiRepo == null ? "" : folioAiRepo;
        if (testrailCaseTypeId == null) testrailCaseTypeId = 1L;
        if (testrailPriorityId == null) testrailPriorityId = 3L;
        testrailLabel = (testrailLabel == null || testrailLabel.isBlank()) ? DEFAULT_TESTRAIL_LABEL : testrailLabel;
        if (enabled == null) enabled = true;
        if (maxCases == null || maxCases < 1) maxCases = 2;
        if (runSpecs == null) runSpecs = false;
        if (cliTimeoutSeconds == null || cliTimeoutSeconds < 1) cliTimeoutSeconds = 1800;
        workspaceDir = workspaceDir == null || workspaceDir.isBlank() ? "cypressTafWork" : workspaceDir;
        // Pinned so a run never depends on the personal ~/.claude/settings.json of whoever hosts it.
        // Same model and effort the team uses for test generation outside the factory.
        cliModel = cliModel == null || cliModel.isBlank() ? "claude-sonnet-5-5" : cliModel;
        cliEffort = cliEffort == null || cliEffort.isBlank() ? "high" : cliEffort;
    }

    /** Defaults only - for tests and for wiring that runs without Spring binding. */
    public static TestAutomationFlowProperties defaults() {
        return new TestAutomationFlowProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
