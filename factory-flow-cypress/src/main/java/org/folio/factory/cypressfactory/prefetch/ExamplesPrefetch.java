package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.cypressfactory.worker.SkillWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
