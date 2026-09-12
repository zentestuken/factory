package org.folio.factory.connectors.testrail;

import java.util.List;
import java.util.Map;

public interface TestRailConnector {

    /**
     * Creates a test case in the given section.
     *
     * @return the created case id
     */
    long addCase(long sectionId, String title, String steps, String expected);

    /**
     * Creates a test run limited to the given case ids.
     *
     * @return the created run id
     */
    long addRun(String name, List<Long> caseIds);

    /**
     * Records results for cases in a run. Map: caseId → passed.
     */
    void addResults(long runId, Map<Long, Boolean> results, String comment);

    /**
     * Creates a test case with additional custom field metadata (e.g. automation type, team, ECS flag).
     * The default implementation delegates to {@link #addCase} and ignores the metadata.
     *
     * @return the created case id
     */
    default long addCaseWithMetadata(long sectionId, String title, String steps, String expected,
                                     Map<String, Object> metadata) {
        return addCase(sectionId, title, steps, expected);
    }

    /**
     * Finds a project label by title, creating it if it does not exist.
     *
     * @return the label id
     */
    default long getOrCreateLabel(String title) {
        throw new UnsupportedOperationException("getOrCreateLabel not supported by this TestRail connector");
    }

    /**
     * Permanently deletes a test case.
     */
    default void deleteCase(long caseId) {
        throw new UnsupportedOperationException("deleteCase not supported by this TestRail connector");
    }

    /**
     * Resolves a section hierarchy path (e.g. {@code "MARC › MARC Bibliographic › Edit MARC bib"})
     * to a section ID by fetching all project sections and walking the path from the root.
     * Accepted separators: {@code ›}, {@code |}, {@code >}, {@code →}.
     * Throws {@link IllegalArgumentException} if the path matches zero or more than one section.
     *
     * @return the matched section id
     */
    default long resolveSectionByPath(String path) {
        throw new UnsupportedOperationException("resolveSectionByPath not supported by this TestRail connector");
    }

    /**
     * Returns all cases in a section. Empty list when connector is not configured.
     */
    default List<TestRailCase> getCases(long sectionId) {
        throw new UnsupportedOperationException("getCases not supported by this TestRail connector");
    }

    /**
     * Returns all sections with their full hierarchical paths (e.g. {@code "MARC › MARC authority"}).
     * Used by coverage loaders to find area-relevant sections by keyword matching.
     * Returns an empty list when the connector is not configured.
     */
    default List<TestRailSectionRef> getSections() {
        throw new UnsupportedOperationException("getSections not supported by this TestRail connector");
    }

    /**
     * Returns all section paths up to depth 2 as human-readable strings
     * (e.g. {@code "MARC › MARC authority › Edit"}), sorted alphabetically.
     * Intended for LLM context — the flat list lets the model identify the right
     * module and sub-area without needing deep leaf-level detail.
     * Returns an empty list when the connector is not configured.
     */
    default List<String> listSectionPaths() {
        throw new UnsupportedOperationException("listSectionPaths not supported by this TestRail connector");
    }

    /**
     * Returns every case type option (id + name) configured on this TestRail account. Global,
     * not project-scoped. IDs drift when TestRail's admin re-templates the field, so callers
     * should resolve by name rather than caching an id across app restarts.
     */
    default List<TestRailFieldOption> getCaseTypes() {
        throw new UnsupportedOperationException("getCaseTypes not supported by this TestRail connector");
    }

    /**
     * Returns every priority option (id + name + short name) configured on this TestRail
     * account. Global, not project-scoped. Same id-drift caveat as {@link #getCaseTypes()}.
     */
    default List<TestRailFieldOption> getPriorities() {
        throw new UnsupportedOperationException("getPriorities not supported by this TestRail connector");
    }
}
