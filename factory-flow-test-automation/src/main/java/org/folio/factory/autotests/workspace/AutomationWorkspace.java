package org.folio.factory.autotests.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The TAF clone automation writes to - separate from {@code cypressTafClone}, which the case flow
 * reads for fragments and must never see unreviewed edits. Each run gets a branch freshly cut from
 * {@code origin/master}; its changes are committed locally at the end (never pushed). Runs take
 * turns: one working tree cannot host two branches at once.
 */
public class AutomationWorkspace {

    static final String TAF_REPO_URL = "https://github.com/folio-org/stripes-testing.git";
    static final String TAF_BRANCH = "master";
    /** A hooks directory that never exists, so git runs no hooks (see {@link #exec}). */
    private static final String NO_HOOKS = ".git/factory-no-hooks";
    private static final Logger log = LoggerFactory.getLogger(AutomationWorkspace.class);

    private final Path root;
    private final String repoUrl;
    private final ReentrantLock lock = new ReentrantLock(true);

    public AutomationWorkspace(Path root) {
        this(root, TAF_REPO_URL);
    }

    AutomationWorkspace(Path root, String repoUrl) {
        this.root = root.toAbsolutePath();
        this.repoUrl = repoUrl;
    }

    public Path root() {
        return root;
    }

    /** Runs {@code body} with the workspace checked out on {@code branch}, holding the workspace lock. */
    public <T> T onBranch(String branch, Function<Path, T> body) {
        lock.lock();
        try {
            prepare(branch);
            return body.apply(root);
        } finally {
            lock.unlock();
        }
    }

    void prepare(String branch) {
        if (!Files.isDirectory(root.resolve(".git"))) {
            log.info("[automation] Cloning {} into {}", repoUrl, root);
            git(root.getParent(), "clone", "--depth=1", "--branch", TAF_BRANCH, repoUrl, root.toString());
            git(root, "config", "core.longpaths", "true");
            git(root, "config", "user.name", "FOLIO AI Factory");
            git(root, "config", "user.email", "factory@localhost");
        }
        git(root, "fetch", "--depth=1", "origin", TAF_BRANCH);
        git(root, "checkout", "-f", "-B", branch, "origin/" + TAF_BRANCH);
        git(root, "clean", "-fd");
        log.info("[automation] Workspace {} on branch {}", root, branch);
    }

    /**
     * Commits everything the run changed and returns the change list, or an empty string when
     * nothing changed.
     */
    public String commitAll(String message) {
        git(root, "add", "-A");
        if (gitExit(root, "diff", "--cached", "--quiet") == 0) {
            return "";
        }
        git(root, "commit", "-q", "-m", message);
        return git(root, "show", "--stat", "--format=", "HEAD").strip();
    }

    /** Drops everything not yet committed - a failed case's half-done edits. */
    public void discardChanges() {
        git(root, "reset", "-q", "--hard");
        git(root, "clean", "-fd");
    }

    /** Spec files ({@code cypress/e2e/**.cy.js}) the last commit added or changed. */
    public List<String> lastCommitSpecs() {
        return git(root, "show", "--name-only", "--diff-filter=AM", "--format=", "HEAD").lines()
                .map(String::strip).filter(f -> f.startsWith("cypress/e2e/") && f.endsWith(".cy.js")).toList();
    }

    /** The commit the current branch points at. */
    public String headSha() {
        return git(root, "rev-parse", "HEAD").strip();
    }

    /** The upstream commit the current branch was cut from. */
    public String baseSha() {
        return git(root, "merge-base", "origin/" + TAF_BRANCH, "HEAD").strip();
    }

    /**
     * Pushes {@code sha} to {@code branch} of another repository (the PR fork). Authentication, when
     * needed, arrives in {@code env} as git config ({@code GIT_CONFIG_*}) - never on the command
     * line or in the URL, the same way Flow D's {@code PublicationGit} does it. The clone is
     * shallow; if the remote lacks the boundary commit, history is fetched once and the push retried.
     */
    public void push(String remoteUrl, String sha, String branch, Map<String, String> env) {
        String[] args = {"push", "--porcelain", remoteUrl, sha + ":refs/heads/" + branch};
        Result result = exec(root, env, args);
        if (result.exit != 0 && result.output.toLowerCase(Locale.ROOT).contains("shallow")) {
            log.info("[automation] The remote lacks this shallow clone's base commit - fetching full history once");
            git(root, "fetch", "--unshallow", "origin", TAF_BRANCH);
            result = exec(root, env, args);
        }
        if (result.exit != 0) {
            throw new IllegalStateException("git push to " + remoteUrl + " failed (" + result.exit + "): "
                    + tail(result.output));
        }
    }

    /** Files changed on this run's branch, compared with the master it was cut from. */
    public String branchChanges() {
        return git(root, "diff", "--stat", "origin/" + TAF_BRANCH + "...HEAD").strip();
    }

    /**
     * Installs the TAF's node_modules the first time specs are to be run. They are gitignored, so
     * the per-run clean keeps them and later runs skip this.
     */
    public void ensureDependencies() {
        if (Files.isDirectory(root.resolve("node_modules"))) return;
        log.info("[automation] Installing TAF dependencies in {} (yarn install - one time, several minutes)", root);
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            command.addAll(List.of("cmd.exe", "/c"));
        }
        command.addAll(List.of("yarn", "install", "--frozen-lockfile"));
        Result result = exec(root, command, 30);
        if (result.exit != 0) {
            throw new IllegalStateException("yarn install failed (" + result.exit + "): " + tail(result.output));
        }
    }

    /**
     * Stages everything and runs the TAF's {@code lint-staged} - exactly what its pre-commit hook
     * runs - with {@code --no-stash}, so the fixes stay even when an error remains. Returns the
     * remaining errors, blank when clean.
     */
    public String runLintStaged() {
        git(root, "add", "-A");
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            command.addAll(List.of("cmd.exe", "/c"));
        }
        command.addAll(List.of("npx", "--no-install", "lint-staged", "--no-stash"));
        Result result = exec(root, command, 10);
        git(root, "add", "-A");
        return result.exit == 0 ? "" : tail(result.output);
    }

    /** Writes a file the TAF gitignores (e.g. {@code environments.js}); it is never committed. */
    public void writeIgnoredFile(String relativePath, String content) {
        try {
            Files.writeString(root.resolve(relativePath), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + relativePath + ": " + e.getMessage(), e);
        }
    }

    private static String tail(String output) {
        String s = output.strip();
        return s.length() > 2000 ? "..." + s.substring(s.length() - 2000) : s;
    }

    private String git(Path dir, String... args) {
        Result result = exec(dir, Map.of(), args);
        if (result.exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed (" + result.exit + "): "
                    + result.output.strip());
        }
        return result.output;
    }

    private int gitExit(Path dir, String... args) {
        return exec(dir, Map.of(), args).exit;
    }

    private record Result(int exit, String output) {}

    /**
     * Every git call runs without hooks: the TAF's {@code yarn install} sets up husky, whose
     * pre-commit hook would otherwise lint and rewrite the generated files on each commit. No
     * credential helper and no terminal prompt either - a missing credential fails instead of
     * opening a login window on the host.
     */
    private static Result exec(Path dir, Map<String, String> env, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.longpaths=true",
                "-c", "core.hooksPath=" + NO_HOOKS, "-c", "credential.helper="));
        command.addAll(List.of(args));
        Map<String, String> withEnv = new java.util.HashMap<>(env);
        withEnv.put("GIT_TERMINAL_PROMPT", "0");
        return exec(dir, command, 10, withEnv);
    }

    private static Result exec(Path dir, List<String> command, long timeoutMinutes) {
        return exec(dir, command, timeoutMinutes, Map.of());
    }

    private static Result exec(Path dir, List<String> command, long timeoutMinutes, Map<String, String> env) {
        String args = String.join(" ", command);
        try {
            Files.createDirectories(dir);
            ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
            builder.environment().putAll(env);
            Process process = builder.start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IllegalStateException(args + " timed out after " + timeoutMinutes + " min");
            }
            return new Result(process.exitValue(), output.get(30, TimeUnit.SECONDS));
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Cannot read the output of " + args + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run " + args + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running " + args, e);
        }
    }
}
