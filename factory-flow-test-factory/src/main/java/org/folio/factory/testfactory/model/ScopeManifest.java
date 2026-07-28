package org.folio.factory.testfactory.model;

import java.util.List;

/**
 * Structured output of the Triage Agent.
 */
public record ScopeManifest(
        String issueKey,
        String summary,
        List<String> components,
        List<String> endpoints,
        String riskLevel,
        List<String> ambiguities,
        List<String> acceptanceCriteria,
        String analysis,
        Boolean ecsEnabled,
        String team) {

    public ScopeManifest {
        components = components == null ? List.of() : components;
        endpoints = endpoints == null ? List.of() : endpoints;
        ambiguities = ambiguities == null ? List.of() : ambiguities;
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : acceptanceCriteria;
        team = team == null ? "" : team;
        ecsEnabled = ecsEnabled == null ? Boolean.FALSE : ecsEnabled;
    }
}
