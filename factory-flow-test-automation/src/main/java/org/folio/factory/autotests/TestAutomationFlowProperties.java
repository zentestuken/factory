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
        String testrailLabel) {    // set FACTORY_CYPRESS_TESTRAIL_LABEL; default "cypressFlow-delete"

    static final String DEFAULT_TESTRAIL_LABEL = "cypressFlow-delete";

    public TestAutomationFlowProperties {
        baseBranch = baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch;
        targetRepo = targetRepo == null ? "" : targetRepo;
        folioAiRepo = folioAiRepo == null ? "" : folioAiRepo;
        if (testrailCaseTypeId == null) testrailCaseTypeId = 1L;
        if (testrailPriorityId == null) testrailPriorityId = 3L;
        testrailLabel = (testrailLabel == null || testrailLabel.isBlank()) ? DEFAULT_TESTRAIL_LABEL : testrailLabel;
    }
}
