package org.folio.factory.cypressfactory.model;

import java.util.List;

/**
 * Structured output of the E2E Spec Agent.
 */
public record CypressTestPlan(
        String issueKey,
        String team,
        Boolean ecsEnabled,
        List<TestCase> cases) {

    public CypressTestPlan {
        cases = cases == null ? List.of() : cases;
        team = team == null ? "" : team;
        ecsEnabled = ecsEnabled == null ? Boolean.FALSE : ecsEnabled;
    }

    public record TestCase(
            String id,
            String title,
            Boolean automatable,
            String testGroup,
            Boolean userJourney,
            Boolean ecsEnabled,
            List<String> preconditions,
            List<Step> steps,
            String automationNotes) {

        public TestCase {
            preconditions = preconditions == null ? List.of() : preconditions;
            steps = steps == null ? List.of() : steps;
            automationNotes = automationNotes == null ? "" : automationNotes;
            testGroup = testGroup == null ? "critical" : testGroup;
            userJourney = userJourney == null ? Boolean.FALSE : userJourney;
            automatable = automatable == null ? Boolean.FALSE : automatable;
            ecsEnabled = ecsEnabled == null ? Boolean.FALSE : ecsEnabled;
        }
    }

    public record Step(String action, String expected) {
        public Step {
            action = action == null ? "" : action;
            expected = expected == null ? "" : expected;
        }
    }
}
