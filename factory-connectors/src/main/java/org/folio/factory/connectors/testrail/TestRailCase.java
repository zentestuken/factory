package org.folio.factory.connectors.testrail;

public record TestRailCase(long id, String title, String preconditions) {
    public TestRailCase {
        preconditions = preconditions == null ? "" : preconditions;
    }
}
