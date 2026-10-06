package org.folio.factory.connectors.jira;

import java.util.List;

public interface JiraConnector {

    JiraIssue getIssue(String issueKey);

    /**
     * The same issue, optionally with Jira rendered HTML body alongside the raw one.
     *
     * <p>Opt-in, and {@code default}, so nothing changes for an existing caller or a connector
     * written against an earlier version: both keep the exact request they always made. Ask for the
     * rendered body only if you need it, because it makes the response noticeably larger.</p>
     *
     * <p>Why it is worth asking for: the rendered HTML is the one representation where an inline
     * image carries a usable identity. In a v2 wiki-markup body an image is {@code !name.png!} and
     * in a v3 ADF body it is a Media Services uuid, but the rendered HTML is
     * {@code <img src=".../attachment/content/122774" alt="name.png">} in both - the real
     * attachment id, the filename, and the position in the document.</p>
     *
     * @param withRenderedBody when true, request Jira {@code renderedFields}; the HTML lands in
     *                         {@link JiraIssue#raw()} under {@code renderedFields.description}
     */
    default JiraIssue getIssue(String issueKey, boolean withRenderedBody) {
        return getIssue(issueKey);
    }

    /**
     * Returns the full issues linked from {@code issueKey} (both inward and outward),
     * capped at 5 to prevent context bloat. Returns an empty list when the issue has
     * no links.
     */
    List<JiraIssue> getLinkedIssues(String issueKey);

    /**
     * The raw bytes of one issue attachment, or {@code null} when it cannot be fetched.
     *
     * <p>{@code default} on purpose: a custom {@code JiraConnector} written against an earlier
     * version still compiles, and only fails if a flow actually asks for an attachment. Callers
     * must treat {@code null} and an exception as the same "no image" outcome and carry on -
     * attachments are grounding material, never a required input.</p>
     *
     * @param contentUrl the attachment's {@code content} URL, exactly as Jira reported it
     */
    default byte[] downloadAttachment(String contentUrl) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not implement downloadAttachment");
    }

    void addComment(String issueKey, String body);

    /**
     * Transitions the issue using the human-readable transition name
     * (e.g. "QA Complete"). No-op names that don't resolve raise an error.
     */
    void transitionIssue(String issueKey, String transitionName);
}
