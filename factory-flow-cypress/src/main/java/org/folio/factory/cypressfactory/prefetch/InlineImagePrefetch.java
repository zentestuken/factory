package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Images embedded <b>inline in the story's own description</b>, fetched so the generation call can
 * actually see them.
 *
 * <p><b>Why inline only.</b> An author who writes "Something like this:" and embeds a picture has
 * put requirements in that picture — UIU-3562 specifies its accordion that way, deferring to two
 * screenshots for the exact labels and column headings a test case has to assert. An attachment
 * that is merely <i>attached</i> carries no such signal: the attachment list accretes error
 * screenshots, HARs and debugging CSVs, so ingesting it would spend tokens on noise. Inline
 * placement is the author's intent, and the rule needs no code of its own — an attachment nothing
 * references simply never matches. Images in <i>linked</i> issues are skipped too: those
 * descriptions are already clipped to a char budget for attention reasons, and an image would blow
 * straight through it.
 *
 * <p><b>Identity comes from Jira's rendered HTML, which is why this works on both v2 and v3.</b>
 * Neither description body gives an image a usable identity: a v2 wiki-markup body has
 * {@code !image-20260430-204850.png|width=844!} (a filename, which at least matches an attachment)
 * and a v3 ADF body has {@code ![](blob:...id=00d03e57-...)} — a Media Services uuid that matches
 * nothing, since attachment entries carry no media id. The rendered body sidesteps both:
 * {@code <img src=".../attachment/content/122774" alt="image-20260430-204850.png">} carries the
 * real attachment id, the filename, and the document position, in either version. So this asks for
 * {@code renderedFields} ({@link JiraConnector#getIssue(String, boolean)}) and pairs the rendered
 * images with the description's image tokens by document order — both lists come from the same
 * document, so order is exact rather than a guess.
 *
 * <p>The wiki-markup path is kept as a fallback for the cases with no rendered body at all: a
 * manually triggered run that passes the story inline, or a {@code JiraConnector} that ignores the
 * opt-in flag. There it still matches on filename, and a v3-style body with no rendered HTML is
 * reported at the gate rather than going quiet.
 */
public class InlineImagePrefetch {

    private static final Logger log = LoggerFactory.getLogger(InlineImagePrefetch.class);

    /**
     * Wiki-markup image embed: {@code !name.png!} or {@code !name.png|width=844,alt="..."!}. The
     * name is anchored to an image extension so this cannot match other {@code !...!} markup.
     */
    private static final Pattern WIKI_IMAGE = Pattern.compile(
            "!([^!|\\s]+\\.(?:png|jpe?g|gif|webp))(\\|[^!]*)?!", Pattern.CASE_INSENSITIVE);

    /**
     * The ADF/v3 rendering of the same thing: a markdown image pointing at a Media Services blob.
     * It carries no filename, so it is only ever paired by position.
     */
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(blob:[^)]*\\)");

    /** The attachment id inside a rendered {@code <img src>}. */
    private static final Pattern ATTACHMENT_ID = Pattern.compile("/attachment/(?:content|thumbnail)/(\\d+)");

    /** Keeps one oversized screenshot from dominating a prompt that is already ~190k tokens. */
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    /**
     * Raised 3 -> 4 on 2026-10-09 against evidence. UIQM-822 embeds four screenshots and the
     * fourth - the one the cap dropped - showed the expected search-box population, which is the
     * entire subject of that story (execution 60283f78). The gate note is what surfaced it. Kept
     * finite because each image is re-sent on every attempt: media rides in the user message and
     * caching is system-only.
     */
    private static final int MAX_IMAGES = 4;

    private final JiraConnector jira;

    public InlineImagePrefetch(JiraConnector jira) {
        this.jira = jira;
    }

    /**
     * @param media        what to attach to the generation call, in description order
     * @param placeholders markup token to the text that replaces it, so the picture stays anchored
     *                     to the sentence that introduced it
     * @param notes        one line per image that could not be presented, for the HITL gate
     */
    public record InlineImages(List<Media> media, Map<String, String> placeholders,
                               List<String> notes) {

        public InlineImages {
            media = media == null ? List.of() : List.copyOf(media);
            placeholders = placeholders == null ? Map.of() : Map.copyOf(placeholders);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        static InlineImages empty() {
            return new InlineImages(List.of(), Map.of(), List.of());
        }

        public boolean isEmpty() {
            return media.isEmpty();
        }
    }

    /** One image as Jira's rendered HTML describes it: real id, real filename, real position. */
    private record RenderedImage(String attachmentId, String filename, String src) {}

    /**
     * Never throws — images are grounding material, never a required input.
     * Notes include the issue key so a gate reviewer knows which ticket the image came from
     * without having to infer it from context.
     */
    public InlineImages load(JiraIssue issue, boolean visionEnabled) {
        try {
            return doLoad(issue, visionEnabled);
        } catch (Exception e) {
            log.warn("[images] Inline image load failed: {}", e.getMessage());
            return InlineImages.empty();
        }
    }

    private InlineImages doLoad(JiraIssue issue, boolean visionEnabled) {
        String issueKey = issue != null && issue.key() != null ? issue.key() : "main ticket";
        String description = issue == null ? null : issue.description();
        if (description == null || description.isBlank()) {
            return InlineImages.empty();
        }

        List<String> tokens = imageTokens(description);
        if (tokens.isEmpty()) {
            return InlineImages.empty();   // nothing inline, so nothing promised and nothing missing
        }

        List<RenderedImage> rendered = renderedImages(issue);
        Map<String, JsonNode> byId = attachmentsBy(issue, "id");
        Map<String, JsonNode> byName = attachmentsBy(issue, "filename");

        List<Media> media = new ArrayList<>();
        Map<String, String> placeholders = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            // Both lists come from the same document, so equal sizes means index i is index i.
            // Unequal sizes mean something is rendering differently than it parses, and only a
            // filename (v2 markup) can still identify the image.
            RenderedImage image = rendered.size() == tokens.size() ? rendered.get(i) : null;
            String filename = image != null ? image.filename() : wikiFilename(token);
            JsonNode attachment = image != null ? byId.get(image.attachmentId()) : null;
            if (attachment == null && filename != null) {
                attachment = byName.get(filename.toLowerCase(Locale.ROOT));
            }
            String label = filename != null ? filename : "image " + (i + 1);

            if (!visionEnabled) {
                placeholders.put(token, placeholder(label, "not sent: image input is off for this run"));
                continue;
            }
            if (media.size() >= MAX_IMAGES) {
                placeholders.put(token, placeholder(label,
                        "not sent: the per-run limit of " + MAX_IMAGES + " images was reached"));
                notes.add(unseen(issueKey, label, "the per-run limit of " + MAX_IMAGES + " images was reached"));
                continue;
            }
            if (attachment == null) {
                placeholders.put(token, placeholder(label, "not sent: could not be identified"));
                notes.add(unidentified(issueKey, label, image == null && rendered.isEmpty()));
                continue;
            }
            String mimeType = attachment.path("mimeType").asString("");
            long size = attachment.path("size").asLong(0);
            if (!mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                placeholders.put(token, placeholder(label, "not sent: not an image (" + mimeType + ")"));
                continue;
            }
            if (size > MAX_IMAGE_BYTES) {
                placeholders.put(token, placeholder(label, "not sent: " + size / 1024 + " KB over the "
                        + MAX_IMAGE_BYTES / 1024 / 1024 + " MB limit"));
                notes.add(unseen(issueKey, label, size / 1024 + " KB exceeds the size limit"));
                continue;
            }
            String contentUrl = attachment.path("content").asString("");
            if (contentUrl.isBlank() && image != null) {
                contentUrl = image.src();
            }
            byte[] bytes = jira.downloadAttachment(contentUrl);
            if (bytes == null || bytes.length == 0) {
                placeholders.put(token, placeholder(label, "not sent: download failed"));
                notes.add(unseen(issueKey, label, "it could not be downloaded from Jira"));
                continue;
            }
            media.add(new Media(parseMimeType(mimeType), new ByteArrayResource(bytes)));
            placeholders.put(token, placeholder(label, "attached to this message"));
        }

        if (!visionEnabled) {
            notes.add(issueKey + ": this story embeds " + tokens.size() + " inline image(s), but "
                    + "image input is disabled for this run (FACTORY_CYPRESS_SEND_IMAGES), so none "
                    + "reached the model. Describe anything they specify in the feedback below.");
        } else if (!media.isEmpty()) {
            log.info("[images] Attached {} of {} inline image(s) from {}",
                    media.size(), tokens.size(), issue.key());
        }
        return new InlineImages(media, placeholders, notes);
    }

    /**
     * Every inline image reference in the description, in order — whichever body format this is.
     * A description never mixes the two, so the first form that matches wins.
     */
    private static List<String> imageTokens(String description) {
        List<String> tokens = new ArrayList<>();
        Matcher wiki = WIKI_IMAGE.matcher(description);
        while (wiki.find()) {
            tokens.add(wiki.group());
        }
        if (!tokens.isEmpty()) {
            return tokens;
        }
        Matcher markdown = MARKDOWN_IMAGE.matcher(description);
        while (markdown.find()) {
            tokens.add(markdown.group());
        }
        return tokens;
    }

    private static String wikiFilename(String token) {
        Matcher m = WIKI_IMAGE.matcher(token);
        return m.matches() || m.find() ? m.group(1) : null;
    }

    /** The rendered {@code <img>} elements, in document order, with their attachment ids. */
    private static List<RenderedImage> renderedImages(JiraIssue issue) {
        List<RenderedImage> images = new ArrayList<>();
        if (issue.raw() == null) {
            return images;
        }
        String html = issue.raw().path("renderedFields").path("description").asString("");
        if (html.isBlank()) {
            return images;
        }
        for (Element img : Jsoup.parse(html).select("img[src]")) {
            String src = img.attr("src");
            Matcher id = ATTACHMENT_ID.matcher(src);
            if (id.find()) {
                String alt = img.attr("alt");
                images.add(new RenderedImage(id.group(1), alt.isBlank() ? null : alt, src));
            }
        }
        return images;
    }

    private static String unseen(String issueKey, String label, String reason) {
        return issueKey + ": inline image " + label + " did not reach the model because " + reason
                + ". If it carries requirements, describe them in the feedback below.";
    }

    /**
     * The note that matters most, because the alternative is silence. No rendered body plus a
     * v3-style description is exactly what a connector change would look like, and it would
     * otherwise be indistinguishable from a story with no pictures.
     */
    private static String unidentified(String issueKey, String label, boolean noRenderedBody) {
        String base = issueKey + ": inline image " + label + " is referenced in the description but "
                + "could not be matched to an attachment, so the model never saw it.";
        if (noRenderedBody) {
            base += " Jira returned no rendered body for this issue, which is where inline images "
                    + "get their attachment id - if the Jira client stopped requesting "
                    + "`expand=renderedFields`, InlineImagePrefetch cannot identify images at all.";
        }
        return base + " If it carries requirements, describe them in the feedback below.";
    }

    /**
     * The substitute for the raw markup. Without it the model gets
     * {@code !image-x.png|width=844!}, which is noise — and when an image IS attached, it has no
     * way to tell which sentence each attachment belongs to. UIU-3562's two images differ entirely
     * by their lead-in ("Something like this" vs "When expanded..."), so position carries meaning.
     */
    private static String placeholder(String label, String state) {
        return "[IMAGE \"" + label + "\" - " + state + "]";
    }

    private static MimeType parseMimeType(String mimeType) {
        try {
            return MimeTypeUtils.parseMimeType(mimeType);
        } catch (Exception e) {
            return MimeTypeUtils.IMAGE_PNG;
        }
    }

    private static Map<String, JsonNode> attachmentsBy(JiraIssue issue, String key) {
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        JsonNode attachments = issue.raw() == null
                ? null : issue.raw().path("fields").path("attachment");
        if (attachments == null || !attachments.isArray()) {
            return byKey;
        }
        for (JsonNode a : attachments) {
            String value = a.path(key).asString("");
            if (!value.isBlank()) {
                byKey.putIfAbsent(value.toLowerCase(Locale.ROOT), a);
            }
        }
        return byKey;
    }
}
