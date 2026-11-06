package org.folio.factory.connectors.testrail;

/**
 * One option of a global TestRail dropdown field ({@code get_case_types}, {@code get_priorities}).
 * {@code shortName} is null for fields that don't carry one (case types); for priorities it is
 * the un-prefixed label ("Medium") where {@code name} may carry an ordinal prefix ("2 - Medium").
 */
public record TestRailFieldOption(long id, String name, String shortName) {
}
