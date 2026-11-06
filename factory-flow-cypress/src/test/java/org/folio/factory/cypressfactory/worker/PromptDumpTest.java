package org.folio.factory.cypressfactory.worker;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PromptDumpTest {

    private static final String REAL_PROMPT_ID = "cypress-coverage-triage";

    private Path freshDir() {
        return Path.of("target", "prompt-dump-unit-" + UUID.randomUUID());
    }

    @Test
    void writeResponseAppendsToTheSameFileWriteCreated() throws Exception {
        Path dir = freshDir();
        UUID executionId = UUID.randomUUID();
        PromptDump.Run run = new PromptDump(true, dir).forExecution(executionId);

        run.write("triage", REAL_PROMPT_ID, Map.of());
        run.writeResponse("triage", "CANDIDATES: C1, C2");

        String content = Files.readString(dir.resolve(executionId.toString()).resolve("1-triage.md"));

        assertThat(content).contains("===== SYSTEM =====").contains("===== USER =====");
        int userIdx = content.indexOf("===== USER =====");
        int responseIdx = content.indexOf("===== RESPONSE =====");
        assertThat(responseIdx).isGreaterThan(userIdx);
        assertThat(content).contains("CANDIDATES: C1, C2");
    }

    @Test
    void writeResponseWithNoPriorWriteIsANoOp() {
        Path dir = freshDir();
        PromptDump.Run run = new PromptDump(true, dir).forExecution(UUID.randomUUID());

        run.writeResponse("never-written", "some response");

        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void writeResponseWhenDisabledIsANoOp() {
        Path dir = freshDir();
        PromptDump.Run run = new PromptDump(false, dir).forExecution(UUID.randomUUID());

        run.write("triage", REAL_PROMPT_ID, Map.of());
        run.writeResponse("triage", "some response");

        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void aSecondRunForTheSameExecutionAppendsInsteadOfOverwriting() throws Exception {
        // An engine-level step retry gets a fresh Run with its sequence back at zero. It used to
        // overwrite the previous attempt file for file: execution 6e2abf8c timed out three times
        // and left three dumps, the last attempt sitting on top of the two that mattered.
        Path dir = Files.createTempDirectory("prompt-dump-retry");
        UUID execution = UUID.randomUUID();
        PromptDump dump = new PromptDump(true, dir);

        PromptDump.Run first = dump.forExecution(execution);
        first.write("router", "cypress-area-router", Map.of());
        first.write("generate-attempt1", "cypress-testcase-gen", Map.of());

        PromptDump.Run retry = dump.forExecution(execution);
        retry.write("router", "cypress-area-router", Map.of());

        try (var files = Files.list(dir.resolve(execution.toString()))) {
            List<String> names = files.map(f -> f.getFileName().toString()).sorted().toList();
            assertThat(names)
                    .contains("1-router.md", "2-generate-attempt1.md", "3-router.md")
                    .hasSize(3);
        }
    }
}
