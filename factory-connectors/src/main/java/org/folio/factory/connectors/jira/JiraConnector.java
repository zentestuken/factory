package org.folio.factory.connectors.jira;

import java.util.List;

public interface JiraConnector {

    JiraIssue getIssue(String issueKey);

    /**
     * Returns the full issues linked from {@code issueKey} (both inward and outward),
     * capped at 5 to prevent context bloat. Returns an empty list when the issue has
     * no links.
     */
    List<JiraIssue> getLinkedIssues(String issueKey);

    void addComment(String issueKey, String body);

    /**
     * Transitions the issue using the human-readable transition name
     * (e.g. "QA Complete"). No-op names that don't resolve raise an error.
     */
    void transitionIssue(String issueKey, String transitionName);
}
