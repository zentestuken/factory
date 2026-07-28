package org.folio.factory.cypressfactory.worker;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintains a shallow clone of folio-org/stripes-testing at {user.dir}/cypressTafClone.
 * Clones on first use; pulls on subsequent startups. The clone runs in a background
 * thread so it does not block application startup.
 *
 * FragmentScannerWorker calls isReady() to decide whether to use local filesystem
 * search or fall back to the GitHub API.
 */
public class TafSyncService {

    private static final Logger log = LoggerFactory.getLogger(TafSyncService.class);

    private static final String TAF_REPO_URL = "https://github.com/folio-org/stripes-testing.git";
    static final String TAF_BRANCH = "master";

    private final Path tafRoot;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public TafSyncService() {
        this.tafRoot = Path.of(System.getProperty("user.dir"), "cypressTafClone");
    }

    @PostConstruct
    public void syncInBackground() {
        Thread t = new Thread(this::sync, "taf-sync");
        t.setDaemon(true);
        t.start();
    }

    private void sync() {
        if (!gitAvailable()) {
            log.warn("[TAF] git not found on PATH — fragment search will use GitHub API fallback");
            return;
        }
        try {
            if (Files.isDirectory(tafRoot.resolve(".git"))) {
                pull();
            } else {
                cloneRepo();
            }
            ready.set(true);
            log.info("[TAF] Ready at {}", tafRoot);
        } catch (Exception e) {
            log.warn("[TAF] Sync failed: {} — fragment search will use GitHub API fallback", e.getMessage());
        }
    }

    private void cloneRepo() throws Exception {
        log.info("[TAF] Cloning {} (shallow) to {}", TAF_REPO_URL, tafRoot);
        Files.createDirectories(tafRoot.getParent());
        run("git", "clone", "--depth=1", "--branch", TAF_BRANCH, TAF_REPO_URL, tafRoot.toString());
    }

    private void pull() throws Exception {
        log.info("[TAF] Pulling latest {} in {}", TAF_BRANCH, tafRoot);
        run("git", "-C", tafRoot.toString(), "pull", "--ff-only", "origin", TAF_BRANCH);
    }

    private boolean gitAvailable() {
        try {
            return run("git", "--version") == 0;
        } catch (IOException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private int run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new RuntimeException("exit " + exit + ": " + String.join(" ", cmd) + "\n" + output.strip());
        }
        return exit;
    }

    public boolean isReady() {
        return ready.get();
    }

    public Path tafRoot() {
        return tafRoot;
    }
}
