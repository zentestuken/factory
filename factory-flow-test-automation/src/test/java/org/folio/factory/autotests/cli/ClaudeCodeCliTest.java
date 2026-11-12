package org.folio.factory.autotests.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCodeCliTest {

    @Test
    void launchesThroughCmdOnlyOnWindowsAndPassesTheAllowlist() {
        List<String> command = ClaudeCodeCli.command(Path.of("work"), List.of("Read", "Bash(git diff:*)"),
                "claude-sonnet-5-5", "high");

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        assertThat(command.getFirst()).isEqualTo(windows ? "cmd.exe" : "claude");
        assertThat(command).containsSequence("--allowedTools", "Read,Bash(git diff:*)")
                .containsSequence("--model", "claude-sonnet-5-5").containsSequence("--effort", "high");
    }

    @Test
    void dropsTheConnectorsNoticeFromTheSummary() {
        String output = "⚠ claude.ai connectors are disabled because ANTHROPIC_API_KEY is set\nI wrote the test.\n";

        assertThat(ClaudeCodeCli.withoutNotice(output)).isEqualTo("I wrote the test.");
    }
}
