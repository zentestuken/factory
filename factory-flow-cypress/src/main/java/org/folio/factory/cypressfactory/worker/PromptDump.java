package org.folio.factory.cypressfactory.worker;

import org.folio.factory.agents.llm.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** The leading number in a dump filename, e.g. {@code 3-generate-attempt1.md}. */
    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+)-");

    public Run forExecution(UUID executionId) {
        return new Run(executionId);
    }

    public final class Run {

        private final UUID executionId;
        private int sequence;
        private boolean sequenceSeeded;
        private final Map<String, Path> filesByLabel = new HashMap<>();

        private Run(UUID executionId) {
            this.executionId = executionId;
        }

        /**
         * Starts this run numbering after whatever is already in the directory.
         *
         * <p>A {@code Run} is created per {@code execute()}, so an engine-level step retry gets a
         * fresh one with {@code sequence} back at zero - and used to overwrite the previous
         * attempt file for file. Execution 6e2abf8c (2026-10-09) timed out three times and left
         * exactly three dumps, the last attempt sitting on top of the two that mattered. The
         * engine attempt number is not visible here ({@code AgentContext} does not carry it, and
         * a debugging aid is no reason to widen a core record), so continuing the numbering is the
         * cheap equivalent: later attempts append, and reading the directory in order still shows
         * what happened when.</p>
         */
        private void seedSequence(Path dir) {
            if (sequenceSeeded) return;
            sequenceSeeded = true;
            try (var existing = Files.list(dir)) {
                sequence = existing.map(f -> f.getFileName().toString())
                        .map(LEADING_NUMBER::matcher)
                        .filter(Matcher::find)
                        .mapToInt(m -> Integer.parseInt(m.group(1)))
                        .max()
                        .orElse(0);
            } catch (Exception e) {
                // Unreadable directory just means numbering restarts - not worth failing over.
            }
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
                seedSequence(dir);
                Path file = dir.resolve("%d-%s.md".formatted(++sequence, sanitize(label)));

                Files.writeString(file, """
                        <!-- prompt: %s | label: %s | execution: %s -->

                        # ===== SYSTEM =====

                        %s

                        # ===== USER =====

                        %s
                        """.formatted(promptId, label, executionId, system, user),
                        StandardCharsets.UTF_8);

                filesByLabel.put(sanitize(label), file);
                log.info("[prompt-dump] {}", file);
            } catch (Exception e) {
                log.warn("[prompt-dump] Could not write '{}' for execution {}: {}",
                        label, executionId, e.getMessage());
            }
        }

        /**
         * Appends the model's raw response to the file {@link #write} created for the same
         * label, so a debugging read sees prompt and response together in one file. A label
         * with no prior {@link #write} call (or one whose write itself failed) is a silent
         * no-op - same "never fail a run" contract as {@link #write}.
         */
        public void writeResponse(String label, String response) {
            if (!enabled) return;
            Path file = filesByLabel.get(sanitize(label));
            if (file == null) return;
            try {
                Files.writeString(file,
                        "\n\n# ===== RESPONSE =====\n\n" + response + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (Exception e) {
                log.warn("[prompt-dump] Could not append response for '{}' execution {}: {}",
                        label, executionId, e.getMessage());
            }
        }
    }

    private static String sanitize(String label) {
        return label.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
