package org.folio.factory.autotests.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class AutomationWorkspaceTest {

    @TempDir
    Path tmp;

    /** A one-commit repo on {@code master} standing in for stripes-testing. */
    public static Path originRepo(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("cypress/e2e"));
        Files.writeString(dir.resolve("cypress/e2e/existing.cy.js"), "it('existing')");
        Files.writeString(dir.resolve(".gitignore"), "node_modules\nenvironments.js\n");
        git(dir, "init", "-q", "-b", "master");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@t", "add", "-A");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
        return dir;
    }

    public static AutomationWorkspace workspace(Path root, Path origin) {
        return new AutomationWorkspace(root, origin.toUri().toString());
    }

    public static String git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", args) + ": " + out);
        return out;
    }

    @Test
    void eachRunStartsFromCleanMasterOnItsOwnBranchAndCommitsLocally() throws Exception {
        AutomationWorkspace workspace = workspace(tmp.resolve("work"), originRepo(tmp.resolve("origin")));

        String stat = workspace.onBranch("factory/UIU-1", root -> {
            write(root.resolve("cypress/e2e/new.cy.js"));
            return workspace.commitAll("first run");
        });
        String secondRun = workspace.onBranch("factory/UIU-2", root ->
                Files.exists(root.resolve("cypress/e2e/new.cy.js")) + " " + workspace.commitAll("nothing"));

        assertThat(stat).contains("cypress/e2e/new.cy.js");
        assertThat(secondRun).isEqualTo("false ");
        assertThat(git(workspace.root(), "branch", "--list")).contains("factory/UIU-1", "factory/UIU-2");
        assertThat(git(workspace.root(), "config", "core.longpaths").strip()).isEqualTo("true");
    }

    @Test
    void uncommittedLeftoversFromACrashedRunAreDiscarded() throws Exception {
        AutomationWorkspace workspace = workspace(tmp.resolve("work"), originRepo(tmp.resolve("origin")));
        workspace.onBranch("factory/crashed", root -> {
            write(root.resolve("cypress/e2e/half-done.cy.js"));
            return null;
        });

        boolean leftover = workspace.onBranch("factory/next", root -> Files.exists(root.resolve("cypress/e2e/half-done.cy.js")));

        assertThat(leftover).isFalse();
    }

    @Test
    void gitHooksSetUpByTheTafNeverRun() throws Exception {
        AutomationWorkspace workspace = workspace(tmp.resolve("work"), originRepo(tmp.resolve("origin")));
        workspace.onBranch("init", root -> null);
        Path hooks = workspace.root().resolve(".husky");
        Files.createDirectories(hooks);
        Files.writeString(hooks.resolve("pre-commit"), "#!/bin/sh\necho blocked by hook\nexit 1\n");
        hooks.resolve("pre-commit").toFile().setExecutable(true);
        git(workspace.root(), "config", "core.hooksPath", ".husky");

        String stat = workspace.onBranch("factory/hooked", root -> {
            write(root.resolve("cypress/e2e/new.cy.js"));
            return workspace.commitAll("would be blocked by husky");
        });

        assertThat(stat).contains("cypress/e2e/new.cy.js");
    }

    private static void write(Path file) {
        try {
            Files.writeString(file, "it('new')");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
