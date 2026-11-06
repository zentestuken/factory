package org.folio.factory.connectors.testrail;

import java.util.List;

public record TestRailCase(long id, String title, String preconditions, List<TestRailStep> steps) {
    public TestRailCase {
        preconditions = preconditions == null ? "" : preconditions;
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
