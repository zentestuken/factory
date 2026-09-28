package org.folio.factory.cypressfactory.worker;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
