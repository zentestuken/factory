package org.folio.factory.autotests.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code claude --print} headless. The prompt goes through a temp file on stdin, which
 * avoids cmd.exe's 8191-character command-line limit. ANTHROPIC_API_KEY is inherited from this
 * JVM's environment.
 */
public class ClaudeCodeCli implements CodingAgentCli {

    /** The CLI prints this when an API key overrides a claude.ai login; it is noise in a summary. */
    private static final String CONNECTORS_NOTICE = "claude.ai connectors are disabled";

    private final long timeoutSeconds;
    private final String model;
    private final String effort;

    public ClaudeCodeCli(long timeoutSeconds, String model, String effort) {
        this.timeoutSeconds = timeoutSeconds;
        this.model = model;
        this.effort = effort;
    }

    @Override
    public String run(String prompt, Path workDir, List<String> allowedTools) {
        Path promptFile = null;
        try {
            promptFile = Files.createTempFile("claude-prompt-", ".txt");
            Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(command(workDir, allowedTools, model, effort))
                    .directory(workDir.toFile())
                    .redirectInput(promptFile.toFile())
                    .redirectErrorStream(true)
                    .start();
            // Read concurrently: a full pipe buffer would otherwise block the CLI and fake a timeout.
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IllegalStateException("claude CLI timed out after " + timeoutSeconds + " s");
            }
            String text = output.get(30, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("claude CLI exited with code " + process.exitValue() + ": " + text);
            }
            return withoutNotice(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the claude CLI", e);
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Failed to run the claude CLI: " + e.getMessage(), e);
        } finally {
            if (promptFile != null) {
                try {
                    Files.deleteIfExists(promptFile);
                } catch (IOException ignored) {
                    // a leftover temp file is harmless
                }
            }
        }
    }

    static List<String> command(Path workDir, List<String> allowedTools, String model, String effort) {
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            command.addAll(List.of("cmd.exe", "/c"));
        }
        command.addAll(List.of("claude", "--print",
                "--allowedTools", String.join(",", allowedTools),
                "--add-dir", workDir.toAbsolutePath().toString(),
                "--model", model, "--effort", effort));
        return command;
    }

    static String withoutNotice(String text) {
        return text.lines().filter(line -> !line.contains(CONNECTORS_NOTICE))
                .reduce((a, b) -> a + "\n" + b).orElse("").strip();
    }
}
