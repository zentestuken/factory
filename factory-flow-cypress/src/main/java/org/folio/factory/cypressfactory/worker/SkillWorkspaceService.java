package org.folio.factory.cypressfactory.worker;

import jakarta.annotation.PostConstruct;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.cypressfactory.prefetch.AreaDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintains a shallow clone of the FOLIO AI skills repo at {user.dir}/skillsClone and
 * resolves skill assets through three tiers: local clone, GitHub API, classpath bundle.
 *
 * <p>Degradation is not uniform. {@code SKILL.md} is a hard dependency — without it there
 * is no MQA process to run, so {@link #requireSkill()} throws rather than letting the
 * generator fall back to a thin prompt and emit plausible-looking cases. Everything else
 * ({@code examples.md}, per-area context files) degrades with a note in the plan's
 * {@code # Analysis} section.</p>
 */
public class SkillWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(SkillWorkspaceService.class);

    private static final String DEFAULT_REPO_URL = "https://github.com/folio-org/folio-eureka-ai-dev.git";
    static final String BRANCH = "master";
    static final String SKILL_DIR = "skills/write-testrail-cases";

    public static final String SKILL_MD = "SKILL.md";
    public static final String EXAMPLES_MD = "references/examples.md";

    public enum Source { CLONE, GITHUB, CLASSPATH }

    public record Asset(String content, Source source, String detail) {}

    /**
     * Phrases in SKILL.md that the factory deltas in {@code prompts/cypress-testcase-gen/system.md}
     * describe overrides for. MQA maintains SKILL.md independently; when one of these disappears
     * the corresponding delta is talking about something that no longer exists, and nothing else
     * would notice — the run would just quietly do the wrong thing.
     *
     * <p>Each phrase is unique in SKILL.md, so a miss is a real signal rather than a coincidence.
     * Keyed by the delta each one anchors, so the warning names what to fix.</p>
     */
    private static final Map<String, String> DELTA_ANCHORS = Map.of(
            "delta 1 - gathering already performed", "read the matching context file",
            "delta 2 - never pause for confirmation", "Wait for confirmation",
            "delta 3 - never touch TestRail", "Ask for the TestRail Section ID");

    private final GitHubConnector gitHub;
    private final CypressFlowProperties properties;
    private final Path cloneRoot;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public SkillWorkspaceService(GitHubConnector gitHub, CypressFlowProperties properties) {
        this.gitHub = gitHub;
        this.properties = properties;
        this.cloneRoot = Path.of(System.getProperty("user.dir"), "skillsClone");
    }

    @PostConstruct
    public void syncInBackground() {
        Thread t = new Thread(this::sync, "skill-sync");
        t.setDaemon(true);
        t.start();
    }

    private void sync() {
        if (!gitAvailable()) {
            log.warn("[SKILL] git not found on PATH - skill assets will use GitHub API / classpath fallback");
            return;
        }
        try {
            if (Files.isDirectory(cloneRoot.resolve(".git"))) {
                pull();
            } else {
                cloneRepo();
            }
            ready.set(true);
            log.info("[SKILL] Ready at {}", skillRoot());
        } catch (Exception e) {
            // A previously cloned tree is still usable when the pull fails (offline, GitHub down).
            if (Files.isRegularFile(skillRoot().resolve(SKILL_MD))) {
                ready.set(true);
                log.warn("[SKILL] Sync failed ({}) - using existing clone, which may be stale", e.getMessage());
            } else {
                log.warn("[SKILL] Sync failed: {} - skill assets will use GitHub API / classpath fallback",
                        e.getMessage());
            }
        }
        warnOnBundleDrift();
        warnOnDeltaDrift();
        warnOnAreaDrift();
    }

    private void cloneRepo() throws Exception {
        String url = repoUrl();
        log.info("[SKILL] Cloning {} (shallow) to {}", url, cloneRoot);
        Files.createDirectories(cloneRoot.getParent());
        run("git", "-c", "core.longpaths=true", "clone", "--depth=1", "--branch", BRANCH, url, cloneRoot.toString());
    }

    private void pull() throws Exception {
        log.info("[SKILL] Pulling latest {} in {}", BRANCH, cloneRoot);
        run("git", "-C", cloneRoot.toString(), "-c", "core.longpaths=true", "pull", "--ff-only", "origin", BRANCH);
    }

    private String repoUrl() {
        String repo = properties.folioAiRepo();
        return repo.isBlank() ? DEFAULT_REPO_URL : "https://github.com/" + repo + ".git";
    }

    private boolean gitAvailable() {
        try {
            return run("git", "--version") == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("exit " + exit + ": " + String.join(" ", cmd) + "\n" + output.strip());
        }
        return exit;
    }

    public boolean isReady() {
        return ready.get();
    }

    public Path skillRoot() {
        return cloneRoot.resolve(SKILL_DIR);
    }

    /**
     * Resolves a skill-relative path (e.g. {@code SKILL.md},
     * {@code references/context/inventory.md}) through clone → GitHub → classpath.
     */
    public Optional<Asset> read(String relativePath) {
        Optional<Asset> fromClone = readFromClone(relativePath);
        if (fromClone.isPresent()) return fromClone;

        Optional<Asset> fromGitHub = readFromGitHub(relativePath);
        if (fromGitHub.isPresent()) return fromGitHub;

        return readFromClasspath(relativePath);
    }

    /**
     * SKILL.md is the entire system prompt. Generating without it would silently produce
     * cases from a thin nameless prompt, so this fails the step instead — engine retry and
     * escalation are the correct response to a transient outage.
     */
    public String requireSkill() {
        return read(SKILL_MD)
                .map(a -> {
                    if (a.source() == Source.CLASSPATH) {
                        log.warn("[SKILL] SKILL.md served from the bundled classpath copy - may be outdated");
                    }
                    return a.content();
                })
                .orElseThrow(() -> new AgentExecutionException(
                        "SKILL.md unavailable from clone, GitHub and classpath - refusing to generate "
                                + "test cases without the skill methodology"));
    }

    private Optional<Asset> readFromClone(String relativePath) {
        if (!ready.get()) return Optional.empty();
        Path file = skillRoot().resolve(relativePath);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.isBlank() ? Optional.empty()
                    : Optional.of(new Asset(content, Source.CLONE, describeClone()));
        } catch (IOException e) {
            log.warn("[SKILL] Could not read {} from clone: {}", relativePath, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Asset> readFromGitHub(String relativePath) {
        if (properties.folioAiRepo().isBlank()) return Optional.empty();
        try {
            String content = gitHub.getFileContent(
                    properties.folioAiRepo(), BRANCH, SKILL_DIR + "/" + relativePath);
            return content == null || content.isBlank() ? Optional.empty()
                    : Optional.of(new Asset(content, Source.GITHUB, properties.folioAiRepo() + "@" + BRANCH));
        } catch (Exception e) {
            log.warn("[SKILL] GitHub fetch of {} failed: {}", relativePath, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Asset> readFromClasspath(String relativePath) {
        String resource = "references/" + fileName(relativePath);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return content.isBlank() ? Optional.empty()
                    : Optional.of(new Asset(content, Source.CLASSPATH, "bundled copy - may be outdated"));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private String describeClone() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", cloneRoot.toString(),
                    "log", "-1", "--format=%h @ %cs").redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.waitFor() == 0 && !out.isBlank() ? "local clone " + out : "local clone";
        } catch (Exception e) {
            return "local clone";
        }
    }

    private static String fileName(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }

    /**
     * Names the factory deltas whose anchor phrase is no longer present in the given skill text.
     * An empty list means every delta still describes something that exists.
     */
    public List<String> unanchoredDeltas(String skillText) {
        if (skillText == null || skillText.isBlank()) return List.copyOf(DELTA_ANCHORS.keySet());
        return DELTA_ANCHORS.entrySet().stream()
                .filter(e -> !skillText.contains(e.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private void warnOnDeltaDrift() {
        read(SKILL_MD).ifPresent(asset -> {
            List<String> unanchored = unanchoredDeltas(asset.content());
            if (!unanchored.isEmpty()) {
                log.warn("[SKILL] SKILL.md no longer contains the text {} describe(s) - "
                                + "review the FACTORY DELTAS block in "
                                + "prompts/cypress-testcase-gen/system.md",
                        unanchored);
            }
        });
    }

    /**
     * Context files the router cannot select (missing from {@link AreaDetector#KNOWN_AREAS}) and
     * known areas with no context file in the given {@code references/context} directory.
     */
    static List<String> areaDrift(Path contextDir) {
        if (!Files.isDirectory(contextDir)) return List.of();
        TreeSet<String> onDisk = new TreeSet<>();
        try (var files = Files.list(contextDir)) {
            files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".md"))
                    .forEach(n -> onDisk.add(n.substring(0, n.length() - 3)));
        } catch (IOException e) {
            return List.of();
        }
        List<String> drift = new java.util.ArrayList<>();
        onDisk.stream().filter(a -> !AreaDetector.KNOWN_AREAS.contains(a))
                .forEach(a -> drift.add("unroutable:" + a));
        AreaDetector.KNOWN_AREAS.stream().filter(a -> !onDisk.contains(a))
                .forEach(a -> drift.add("missing-file:" + a));
        return drift;
    }

    private void warnOnAreaDrift() {
        List<String> drift = areaDrift(skillRoot().resolve("references/context"));
        if (!drift.isEmpty()) {
            log.warn("[SKILL] Area context files out of sync with AreaDetector.KNOWN_AREAS {} - "
                    + "'unroutable' files can never be selected by the router; "
                    + "update AreaDetector (KNOWN_AREAS, PROJECT_KEY_AREAS, AREA_KEYWORDS)", drift);
        }
    }

    /** Cheap drift signal: the bundled fallback silently rotting is the real hazard, not availability. */
    private void warnOnBundleDrift() {
        readFromClone(SKILL_MD).ifPresent(live -> readFromClasspath(SKILL_MD).ifPresent(bundled -> {
            int liveLen = live.content().length();
            int bundledLen = bundled.content().length();
            if (liveLen > 0 && Math.abs(liveLen - bundledLen) * 100.0 / liveLen > 10.0) {
                log.warn("[SKILL] Bundled SKILL.md is {} chars vs {} live (>10% drift) - "
                                + "run scripts/update-cypress-testcase-skill-bundle.sh",
                        bundledLen, liveLen);
            }
        }));
    }
}
