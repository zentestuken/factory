package org.folio.factory.cypressfactory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.cypress")
public record CypressFlowProperties(
        String targetRepo,
        String baseBranch,
        String folioAiRepo,
        Long testrailSectionId,
        String testrailCaseTypeName, // set FACTORY_CYPRESS_TESTRAIL_CASE_TYPE_NAME; default "Functional".
                                      // Resolved against TestRail's own get_case_types at sync time,
                                      // not a hardcoded id - TestRail ids drift when re-templated.
        String testrailPriorityName, // set FACTORY_CYPRESS_TESTRAIL_PRIORITY_NAME; default "Medium".
                                      // Same by-name resolution as testrailCaseTypeName.
        String testrailLabel,      // set FACTORY_CYPRESS_TESTRAIL_LABEL; default "cypressFlow-delete"
        Integer maxReviewIterations, // set FACTORY_CYPRESS_MAX_REVIEW_ITERATIONS; default 3; 0 = skip agent review
        Set<String> refsExcludedProjects) { // set FACTORY_CYPRESS_REFS_EXCLUDED_PROJECTS; comma-separated Jira
                                            // project keys never added to TestRail refs; default FAT

    static final String DEFAULT_TESTRAIL_CASE_TYPE_NAME = "Functional";
    static final String DEFAULT_TESTRAIL_PRIORITY_NAME = "Medium";
    static final String DEFAULT_TESTRAIL_LABEL = "cypressFlow-delete";
    static final int DEFAULT_MAX_REVIEW_ITERATIONS = 3;
    static final Set<String> DEFAULT_REFS_EXCLUDED_PROJECTS = Set.of("FAT");

    public CypressFlowProperties {
        baseBranch = baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch;
        targetRepo = targetRepo == null ? "" : targetRepo;
        folioAiRepo = folioAiRepo == null ? "" : folioAiRepo;
        testrailCaseTypeName = (testrailCaseTypeName == null || testrailCaseTypeName.isBlank())
                ? DEFAULT_TESTRAIL_CASE_TYPE_NAME : testrailCaseTypeName.strip();
        testrailPriorityName = (testrailPriorityName == null || testrailPriorityName.isBlank())
                ? DEFAULT_TESTRAIL_PRIORITY_NAME : testrailPriorityName.strip();
        testrailLabel = (testrailLabel == null || testrailLabel.isBlank()) ? DEFAULT_TESTRAIL_LABEL : testrailLabel;
        if (testrailLabel.length() > 20) {
            throw new IllegalArgumentException(
                    "FACTORY_CYPRESS_TESTRAIL_LABEL is " + testrailLabel.length() + " characters" +
                    " (\"" + testrailLabel + "\") - TestRail enforces a 20-character limit." +
                    " Shorten it and restart.");
        }
        if (maxReviewIterations == null || maxReviewIterations < 0) maxReviewIterations = DEFAULT_MAX_REVIEW_ITERATIONS;
        refsExcludedProjects = refsExcludedProjects == null
                ? DEFAULT_REFS_EXCLUDED_PROJECTS
                : refsExcludedProjects.stream()
                        .filter(p -> p != null && !p.isBlank())
                        .map(p -> p.strip().toUpperCase(Locale.ROOT))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
