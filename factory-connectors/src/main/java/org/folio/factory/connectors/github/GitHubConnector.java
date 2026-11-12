package org.folio.factory.connectors.github;

import java.util.Map;

import java.util.List;

public interface GitHubConnector {

    /**
     * Creates {@code newBranch} pointing at the head of {@code baseBranch}.
     *
     * @param repo "owner/name"
     */
    void createBranch(String repo, String baseBranch, String newBranch);

    /**
     * Commits the given files (path → content) to the branch, one commit per call.
     */
    void commitFiles(String repo, String branch, Map<String, String> files, String message);

    /**
     * @return the created pull request's HTML URL
     */
    String createPullRequest(String repo, String headBranch, String baseBranch, String title, String body);

    /**
     * Returns the decoded text content of a file, or an empty string if the file does not exist.
     */
    String getFileContent(String repo, String branch, String path);

    /**
     * Returns the names of files (not subdirectories) in the directory at {@code path},
     * or an empty list if the path does not exist or is not a directory.
     */
    List<String> listDirectory(String repo, String branch, String path);

    /** Brings a fork's {@code branch} up to date with its upstream (GitHub "Sync fork"). */
    default void syncFork(String repo, String branch) {
        throw new UnsupportedOperationException("GitHub fork sync is not configured");
    }

    /** Find an open PR inside the destination repository before creating another. */
    default java.util.Optional<String> findOpenPullRequest(String repo, String headBranch, String baseBranch) {
        throw new UnsupportedOperationException("GitHub pull request lookup is not configured");
    }
}
