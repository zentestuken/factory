package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.llm.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the fully rendered prompts of a run to disk so "why did it do that?" can be answered
 * by reading what the model actually received.
 *
 * <p>Roughly half of the generation prompt is assembled at runtime — the skill text, context
 * files, the raw ticket and the conditional feedback blocks — so the {@code .md} templates on
 * the {@code /prompts} page are only part of the story.</p>
 *
 * <p>Off unless {@code FACTORY_DEBUG_ENABLED=true}; when off nothing is rendered and nothing is
 * written. Deliberately plain files rather than artifacts: the question these answer is usually
 * "how did this run differ from the last one?", which is a {@code diff} between two directories.
 * Artifacts would also have to be declared outputs, and the engine requires declared outputs on
 * every run — there is no conditional artifact.</p>
 */
public class PromptDump {

    private static final Logger log = LoggerFactory.getLogger(PromptDump.class);

    private final boolean enabled;
    private final Path root;

    public PromptDump(boolean enabled, Path root) {
        this.enabled = enabled;
        this.root = root;
    }

    /** Per-execution sink. Created fresh in {@code execute()}, so it holds no shared state. */
    public Run forExecution(UUID executionId) {
        return new Run(executionId);
    }

    public final class Run {

        private final UUID executionId;
        private int sequence;

        private Run(UUID executionId) {
            this.executionId = executionId;
        }

        /**
         * Renders {@code promptId}'s system and user templates with {@code variables} — the same
         * rendering the model call will do — and writes both to a numbered file. Never throws:
         * a debugging aid must not be able to fail a run.
         */
        public void write(String label, String promptId, Map<String, Object> variables) {
            if (!enabled) return;
            try {
                String system = PromptLoader.render(PromptLoader.load(promptId, "system"), variables);
                String user = PromptLoader.render(PromptLoader.load(promptId, "user"), variables);

                Path dir = root.resolve(executionId.toString());
                Files.createDirectories(dir);
                Path file = dir.resolve("%d-%s.md".formatted(++sequence, sanitize(label)));

                Files.writeString(file, """
                        <!-- prompt: %s | label: %s | execution: %s -->

                        # ===== SYSTEM =====

                        %s

                        # ===== USER =====

                        %s
                        """.formatted(promptId, label, executionId, system, user),
                        StandardCharsets.UTF_8);

                log.info("[prompt-dump] {}", file);
            } catch (Exception e) {
                log.warn("[prompt-dump] Could not write '{}' for execution {}: {}",
                        label, executionId, e.getMessage());
            }
        }
    }

    private static String sanitize(String label) {
        return label.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
