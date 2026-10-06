package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inline-only image ingestion. The rule under test is a scoping decision, not a heuristic: an image
 * referenced from the description is part of the spec, an attachment nobody references is accretion.
 * Identity comes from Jira's rendered HTML so the same code works on v2 and v3 bodies. Every "did
 * not reach the model" path must also leave a trace, because an image that silently vanishes is
 * worse than one that was never supported.
 */
class InlineImagePrefetchTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    /** The v3 body: a Media Services uuid, which on its own matches no attachment. */
    private static final String V3_BODY =
            "Something like this:\n\n![](blob:https://media.staging.atl-paas.net/?type=file"
            + "&id=00d03e57-f7e5-4f07-bcd9-3bd99638f283&width=844)\n";

    /** An issue with no rendered body - the fallback path (inline trigger, or an older connector). */
    private static JiraIssue issue(String description, String attachmentsJson) {
        return issue(description, attachmentsJson, null);
    }

    /** As Jira answers when the rendered body is requested: both representations present. */
    private static JiraIssue issue(String description, String attachmentsJson, String renderedHtml) {
        String rendered = renderedHtml == null ? ""
                : ",\"renderedFields\":{\"description\":" + quote(renderedHtml) + "}";
        JsonNode raw = MAPPER.readTree(
                "{\"fields\":{\"attachment\":" + attachmentsJson + "}" + rendered + "}");
        return new JiraIssue("UIU-3562", "Add a User capabilities accordion", description,
                "Open", "Story", List.of(), raw);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String renderedImg(String attachmentId, String filename) {
        return "<p>Something like this:</p><p><img src=\"https://jira.example/rest/api/3/attachment/"
                + "content/" + attachmentId + "\" alt=\"" + filename + "\" width=\"671\"></p>";
    }

    private static String attachment(String filename, String mimeType, int size) {
        return attachment("1", filename, mimeType, size);
    }

    private static String attachment(String id, String filename, String mimeType, int size) {
        return "{\"id\":\"" + id + "\",\"filename\":\"" + filename + "\",\"mimeType\":\"" + mimeType
                + "\",\"size\":" + size
                + ",\"content\":\"https://jira.example/rest/api/2/attachment/content/" + id + "\"}";
    }

    private static JiraConnector connectorReturning(byte[] bytes) {
        return new JiraConnector() {
            @Override
            public JiraIssue getIssue(String issueKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<JiraIssue> getLinkedIssues(String issueKey) {
                return List.of();
            }

            @Override
            public byte[] downloadAttachment(String contentUrl) {
                return bytes;
            }

            @Override
            public void addComment(String issueKey, String body) {
            }

            @Override
            public void transitionIssue(String issueKey, String transitionName) {
            }
        };
    }

    // -- v2 wiki-markup bodies ------------------------------------------------

    @Test
    void attachesAnImageReferencedInlineInTheDescription() {
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue(
                "Something like this:\n\n!image-20260430-204850.png|width=844,height=812!\n",
                "[" + attachment("122774", "image-20260430-204850.png", "image/png", 66950) + "]",
                renderedImg("122774", "image-20260430-204850.png")), true);

        assertThat(images.media()).hasSize(1);
        assertThat(images.media().get(0).getMimeType().toString()).isEqualTo("image/png");
        assertThat(images.notes()).isEmpty();
    }

    @Test
    void matchesOnFilenameWhenNoRenderedBodyIsAvailable() {
        // A manually triggered run passes the story inline, so there is no rendered body at all.
        // The v2 markup still names the file, so the fallback can identify it.
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue("See !shot.png!",
                "[" + attachment("shot.png", "image/png", 1000) + "]"), true);

        assertThat(images.media()).hasSize(1);
    }

    @Test
    void replacesTheMarkupWithALabelledPlaceholderAtItsOriginalPosition() {
        // The two pictures of UIU-3562 mean different things purely by their lead-in sentence, so
        // the model has to be able to tell which attachment belongs where.
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue(
                "Something like this:\n\n!collapsed.png|width=844!\n",
                "[" + attachment("collapsed.png", "image/png", 1000) + "]"), true);

        assertThat(images.placeholders()).containsEntry("!collapsed.png|width=844!",
                "[IMAGE \"collapsed.png\" - attached to this message]");
    }

    @Test
    void pairsTwoImagesWithTheirOwnAttachmentsInDocumentOrder() {
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue(
                "Collapsed: !image-a.png|width=844!\nExpanded: !image-b.png|width=1213!",
                "[" + attachment("122774", "image-a.png", "image/png", 1000) + ","
                        + attachment("122775", "image-b.png", "image/png", 2000) + "]",
                "<p><img src=\"https://jira.example/rest/api/3/attachment/content/122774\" "
                        + "alt=\"image-a.png\"></p>"
                        + "<p><img src=\"https://jira.example/rest/api/3/attachment/content/122775\" "
                        + "alt=\"image-b.png\"></p>"), true);

        assertThat(images.media()).hasSize(2);
        assertThat(images.placeholders())
                .containsEntry("!image-a.png|width=844!",
                        "[IMAGE \"image-a.png\" - attached to this message]")
                .containsEntry("!image-b.png|width=1213!",
                        "[IMAGE \"image-b.png\" - attached to this message]");
    }

    // -- v3 ADF bodies, identified through the rendered HTML ------------------

    @Test
    void attachesAV3AdfImageByPairingItWithTheRenderedBody() {
        // The v3 body identifies the image only by a Media Services uuid, which matches no
        // attachment. The rendered body carries the real attachment id, so the pairing is exact.
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue(V3_BODY,
                "[" + attachment("122774", "image-20260430-204850.png", "image/png", 66950) + "]",
                renderedImg("122774", "image-20260430-204850.png")), true);

        assertThat(images.media()).hasSize(1);
        assertThat(images.placeholders()).containsValue(
                "[IMAGE \"image-20260430-204850.png\" - attached to this message]");
    }

    @Test
    void reportsAV3BodyWhenNoRenderedBodyCameBack() {
        // Without the rendered body a v3 description is unmatchable - and silence here would look
        // exactly like a story with no pictures, which is how a connector change deletes a feature.
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue(V3_BODY,
                "[" + attachment("122774", "image-20260430-204850.png", "image/png", 66950) + "]"), true);

        assertThat(images.media()).isEmpty();
        assertThat(images.notes()).singleElement().asString()
                .contains("no rendered body")
                .contains("expand=renderedFields");
    }

    // -- scoping and failure reporting ---------------------------------------

    @Test
    void ignoresAnAttachmentThatTheDescriptionNeverReferences() {
        // Tier 3, and it needs no rule of its own: the attachment list accretes error screenshots
        // and debugging files, and nothing references them, so nothing matches them.
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue(
                "No pictures in this description at all.",
                "[" + attachment("error-screenshot.png", "image/png", 1000) + "]"), true);

        assertThat(images.isEmpty()).isTrue();
        assertThat(images.notes()).isEmpty();   // nothing was promised, so nothing is missing
    }

    @Test
    void reportsAnInlineReferenceWithNoMatchingAttachment() {
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue("See !missing.png!", "[]"), true);

        assertThat(images.media()).isEmpty();
        assertThat(images.notes()).singleElement().asString()
                .contains("missing.png")
                .contains("could not be matched to an attachment");
        assertThat(images.placeholders()).containsEntry("!missing.png!",
                "[IMAGE \"missing.png\" - not sent: could not be identified]");
    }

    @Test
    void reportsADownloadThatCameBackEmpty() {
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(null));

        var images = prefetch.load(issue("See !shot.png!",
                "[" + attachment("shot.png", "image/png", 1000) + "]"), true);

        assertThat(images.media()).isEmpty();
        assertThat(images.notes()).singleElement().asString()
                .contains("shot.png")
                .contains("could not be downloaded");
    }

    @Test
    void sendsNothingAndSaysSoWhenImageInputIsDisabled() {
        // A model without image support rejects the call outright rather than ignoring the image,
        // so this switch has to exist - and has to be visible when it is the reason.
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue("See !shot.png!",
                "[" + attachment("shot.png", "image/png", 1000) + "]"), false);

        assertThat(images.media()).isEmpty();
        assertThat(images.notes()).singleElement().asString()
                .contains("image input is disabled")
                .contains("FACTORY_CYPRESS_SEND_IMAGES");
        assertThat(images.placeholders()).containsEntry("!shot.png!",
                "[IMAGE \"shot.png\" - not sent: image input is off for this run]");
    }

    @Test
    void skipsANonImageAttachmentReferencedWithImageMarkup() {
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue("See !trace.png!",
                "[" + attachment("trace.png", "application/octet-stream", 1000) + "]"), true);

        assertThat(images.media()).isEmpty();
    }

    @Test
    void skipsAnImageOverTheSizeLimit() {
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue("See !huge.png!",
                "[" + attachment("huge.png", "image/png", 9_000_000) + "]"), true);

        assertThat(images.media()).isEmpty();
        assertThat(images.notes()).singleElement().asString().contains("exceeds the size limit");
    }

    @Test
    void capsHowManyImagesOneRunCanAttach() {
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));
        StringBuilder description = new StringBuilder();
        StringBuilder attachments = new StringBuilder("[");
        for (int i = 1; i <= 5; i++) {
            description.append("Shot ").append(i).append(": !shot").append(i).append(".png!\n");
            attachments.append(i > 1 ? "," : "")
                    .append(attachment(String.valueOf(i), "shot" + i + ".png", "image/png", 1000));
        }

        var images = prefetch.load(issue(description.toString(), attachments.append("]").toString()), true);

        assertThat(images.media()).hasSize(3);
        assertThat(images.notes()).hasSize(2);
        assertThat(images.notes().get(0)).contains("per-run limit of 3 images");
    }

    @Test
    void doesNotTreatOtherWikiMarkupAsAnImage() {
        // `!...!` is also emphasis and other markup in wiki syntax; only an image extension counts.
        InlineImagePrefetch prefetch = new InlineImagePrefetch(connectorReturning(PNG));

        var images = prefetch.load(issue("The field is required!Really!", "[]"), true);

        assertThat(images.isEmpty()).isTrue();
        assertThat(images.notes()).isEmpty();
    }

    @Test
    void neverThrowsWhenTheConnectorBlowsUp() {
        JiraConnector exploding = new JiraConnector() {
            @Override
            public JiraIssue getIssue(String issueKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<JiraIssue> getLinkedIssues(String issueKey) {
                return List.of();
            }

            @Override
            public void addComment(String issueKey, String body) {
            }

            @Override
            public void transitionIssue(String issueKey, String transitionName) {
            }
            // downloadAttachment is left as the interface default, which throws
        };

        var images = new InlineImagePrefetch(exploding).load(issue("See !shot.png!",
                "[" + attachment("shot.png", "image/png", 1000) + "]"), true);

        assertThat(images.isEmpty()).isTrue();
    }
}
