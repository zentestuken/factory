package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.cypressfactory.worker.SkillWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads the golden-example test cases that anchor generation style.
 *
 * <p>Demoted from the former {@code cypress-examples-loader} worker; the clone → GitHub →
 * classpath chain now lives in {@link SkillWorkspaceService}. Examples are a soft
 * dependency: without them verification depth and formatting degrade, but cases are still
 * structurally valid.</p>
 */
public class ExamplesPrefetch {

    private static final Logger log = LoggerFactory.getLogger(ExamplesPrefetch.class);

    public record Examples(String text, String sourceNote) {}

    private final SkillWorkspaceService skills;

    public ExamplesPrefetch(SkillWorkspaceService skills) {
        this.skills = skills;
    }

    /**
     * The per-area golden cases ({@code references/examples/<area>.md}, skill v2). Only some areas
     * have one, so absence is normal and silent. Area-specific, so the caller puts this in the user
     * message - the system prompt is shared, and cached, across every area.
     */
    public Examples loadForAreas(List<String> areas) {
        StringBuilder text = new StringBuilder();
        List<String> found = new ArrayList<>();
        for (String area : areas == null ? List.<String>of() : areas) {
            skills.read("references/examples/" + area + ".md").ifPresent(a -> {
                found.add(area);
                text.append("### Golden case for the `").append(area).append("` area\n\n")
                        .append(a.content().strip()).append("\n\n");
            });
        }
        return found.isEmpty()
                ? new Examples("_No area-specific golden case exists for this area; use the "
                        + "cross-area examples in the system prompt._", "none for " + areas)
                : new Examples(text.toString(), "examples/" + String.join(".md, examples/", found) + ".md");
    }

    public Examples load() {
        return skills.read(SkillWorkspaceService.EXAMPLES_MD)
                .map(a -> new Examples(a.content(), "examples.md (" + a.detail() + ")"))
                .orElseGet(() -> {
                    log.warn("[examples] Golden examples unavailable from clone, GitHub and classpath");
                    return new Examples(
                            "_No golden examples available. Follow the style rules in the skill text "
                                    + "and state this limitation in `# Analysis`._",
                            "examples.md (not found)");
                });
    }
}
