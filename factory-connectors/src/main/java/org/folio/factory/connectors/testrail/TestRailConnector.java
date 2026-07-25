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
}
