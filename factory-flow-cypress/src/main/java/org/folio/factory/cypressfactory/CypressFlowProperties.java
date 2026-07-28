package org.folio.factory.cypressfactory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.cypress")
public record CypressFlowProperties(
        String targetRepo,
        String baseBranch,
        String folioAiRepo,
        Long testrailSectionId) {

    public CypressFlowProperties {
        baseBranch = baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch;
        targetRepo = targetRepo == null ? "" : targetRepo;
        folioAiRepo = folioAiRepo == null ? "" : folioAiRepo;
    }
}
