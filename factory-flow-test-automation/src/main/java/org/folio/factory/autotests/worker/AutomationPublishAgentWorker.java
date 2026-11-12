package org.folio.factory.autotests.worker;

import org.folio.factory.autotests.TestAutomationFlowProperties;
import org.folio.factory.autotests.workspace.AutomationWorkspace;
import org.folio.factory.connectors.ConnectorNotConfiguredException;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Opens a pull request with the approved autotests. Runs after gate 2, so only on approve.
 *
 * <p>The run's branch is pushed as-is with git - one commit per case, any kind of change - to a
 * branch of {@code FACTORY_CYPRESS_TARGET_REPO} (a stripes-testing fork, synced with upstream
 * first), then the PR is opened through the GitHub API. The token reaches git as an HTTP header in
 * the process environment, never on the command line or in the URL - the way Flow D publishes.</p>
 *
 * <p>No target repo or no GitHub token: skipped ({@code CONNECTOR_SKIPPED}), the run still
 * completes and the specs stay on the local branch. A push or GitHub error is reported, not thrown.</p>
 */
public class AutomationPublishAgentWorker implements AgentWorker {

    public static final String ID = "cypress-automation-publish";
    public static final String PUBLISH = "automation_publish.json";
    private static final Logger log = LoggerFactory.getLogger(AutomationPublishAgentWorker.class);
    private static final JsonMapper JSON = new JsonMapper();

    private final GitHubConnector gitHub;
    private final AutomationWorkspace workspace;
    private final TestAutomationFlowProperties properties;
    private final String testRailBaseUrl;
    private final String gitToken;
    /** Where repos are pushed, e.g. {@code https://github.com/}; the repo and ".git" are appended. */
    private final String gitHost;
    private final AuditLog auditLog;

    public AutomationPublishAgentWorker(GitHubConnector gitHub, AutomationWorkspace workspace,
                                        TestAutomationFlowProperties properties, String testRailBaseUrl,
                                        String gitToken, String gitHost, AuditLog auditLog) {
        this.gitHub = gitHub;
        this.workspace = workspace;
        this.properties = properties;
        this.testRailBaseUrl = testRailBaseUrl == null ? "" : testRailBaseUrl.replaceAll("/+$", "");
        this.gitToken = gitToken == null ? "" : gitToken;
        this.gitHost = gitHost.endsWith("/") ? gitHost : gitHost + "/";
        this.auditLog = auditLog;
    }

    /** The git host for a GitHub API base URL: api.github.com -> github.com; Enterprise drops /api/v3. */
    public static String gitHostFor(String apiBaseUrl) {
        String url = apiBaseUrl.replaceAll("/+$", "");
        if (url.equals("https://api.github.com")) return "https://github.com/";
        return url.replaceFirst("/api/v3$", "") + "/";
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        JsonNode results = JSON.readTree(context.requireInput(AutomateCasesAgentWorker.RESULTS).content());
        JsonNode selection = JSON.readTree(context.requireInput(AutomationSelectAgentWorker.SELECTION).content());
        String repo = properties.targetRepo();
        String base = properties.baseBranch();

        List<JsonNode> done = new ArrayList<>();
        results.path("cases").forEach(c -> {
            if (c.path("error").isNull() || c.path("error").isMissingNode()) done.add(c);
        });
        String baseSha = results.path("baseSha").asString("");
        String headSha = results.path("headSha").asString("");
        if (done.isEmpty() || baseSha.isBlank() || baseSha.equals(headSha)) {
            return outcome(context, "skipped", "nothing to publish - no case was automated", repo, null, null);
        }
        String localBranch = results.path("branch").asString("");
        if (repo.isBlank()) {
            return outcome(context, "skipped", "FACTORY_CYPRESS_TARGET_REPO is not set - the specs stay on the local branch "
                    + localBranch, repo, null, null);
        }
        if (gitToken.isBlank()) {
            return outcome(context, "skipped", "FACTORY_CONNECTORS_GITHUB_TOKEN is not set - the specs stay on the local branch "
                    + localBranch, repo, null, null);
        }

        String issueKey = selection.path("issueKey").asString("");
        List<String> caseIds = done.stream().map(c -> c.path("caseId").asString("")).toList();
        String branch = remoteBranch(issueKey, caseIds, context);
        String title = (issueKey.isBlank() ? "" : "[" + issueKey + "] ") + "Automate " + String.join(", ", caseIds);
        try {
            Optional<String> existing = findOpenPullRequest(repo, branch, base);
            if (existing.isPresent()) {
                return outcome(context, "done", "PR already open", repo, branch, existing.get());
            }
            syncFork(repo, base);
            workspace.push(gitHost + repo + ".git", headSha, branch, authentication());
            String url = gitHub.createPullRequest(repo, branch, base, title, body(done, issueKey, results, context));
            return outcome(context, "done", "PR opened", repo, branch, url);
        } catch (ConnectorNotConfiguredException e) {
            return outcome(context, "skipped", e.getMessage(), repo, null, null);
        } catch (RestClientException | IllegalStateException e) {
            return outcome(context, "failed", e.getMessage(), repo, branch, null);
        }
    }

    /** The token as an HTTP header, scoped to the git host, passed through the environment. */
    private Map<String, String> authentication() {
        String header = "Authorization: Basic " + Base64.getEncoder()
                .encodeToString(("x-access-token:" + gitToken).getBytes(StandardCharsets.UTF_8));
        return Map.of("GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "http." + gitHost + ".extraHeader",
                "GIT_CONFIG_VALUE_0", header);
    }

    private Optional<String> findOpenPullRequest(String repo, String branch, String base) {
        try {
            return gitHub.findOpenPullRequest(repo, branch, base);
        } catch (UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    /** Best effort: a repo that isn't a fork, or is already in sync, still gets its PR. */
    private void syncFork(String repo, String base) {
        try {
            gitHub.syncFork(repo, base);
        } catch (RestClientException | UnsupportedOperationException e) {
            log.info("[automation] Could not sync {} with upstream ({}) - continuing", repo, e.getMessage());
        }
    }

    /** Unique per run, so a later run of the same story never collides with an earlier PR. */
    static String remoteBranch(String issueKey, List<String> caseIds, AgentContext context) {
        String base = issueKey.isBlank() ? caseIds.getFirst() : issueKey;
        return "factory/" + base.replaceAll("[^A-Za-z0-9._-]", "-") + "-"
                + context.executionId().toString().substring(0, 8);
    }

    private String body(List<JsonNode> done, String issueKey, JsonNode results, AgentContext context) {
        StringBuilder md = new StringBuilder();
        if (!issueKey.isBlank()) md.append("Story: ").append(issueKey).append("\n\n");
        md.append("| Case | Spec | Review |\n|---|---|---|\n");
        for (JsonNode c : done) {
            String id = c.path("caseId").asString("");
            String link = testRailBaseUrl.isBlank() ? id
                    : "[" + id + "](" + testRailBaseUrl + "/index.php?/cases/view/" + id.substring(1) + ")";
            String env = c.path("runEnvironment").asString("");
            md.append("| ").append(link).append(" ").append(c.path("title").asString("").replace("|", "\\|"))
                    .append(" | `").append(c.path("testFile").asString("")).append("` | ")
                    .append(c.path("verdict").asString(""))
                    .append(c.path("ran").asBoolean(false) ? " (run on " + env + ")" : " (not run)")
                    .append(" |\n");
        }
        for (JsonNode c : done) {
            List<String> findings = new ArrayList<>();
            c.path("findings").forEach(f -> findings.add(f.asString("")));
            if (!findings.isEmpty()) {
                md.append("\n**").append(c.path("caseId").asString("")).append(" review findings**\n");
                findings.forEach(f -> md.append("- ").append(f).append('\n'));
            }
        }
        md.append("\nOne commit per case.\n\n---\nGenerated by the FOLIO AI factory (execution `")
                .append(context.executionId()).append("`) and approved at its review gate. Built on upstream `")
                .append(results.path("baseSha").asString("")).append("`.\n");
        return md.toString();
    }

    private AgentResult outcome(AgentContext context, String status, String detail, String repo,
                                String branch, String url) {
        auditLog.record(context.executionId(),
                "skipped".equals(status) ? AuditEventType.CONNECTOR_SKIPPED : AuditEventType.CONNECTOR_ACTION,
                context.stepId(), Map.of("connector", "github", "action", "open PR", "status", status,
                        "detail", url != null ? url : detail));
        if ("failed".equals(status)) {
            log.warn("[automation] PR not opened in {}: {} - the specs stay on the local branch", repo, detail);
        } else {
            log.info("[automation] {}", url != null ? detail + ": " + url : "PR " + status + ": " + detail);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("status", status);
        json.put("detail", detail);
        json.put("repo", repo);
        json.put("branch", branch);
        json.put("prUrl", url);
        return AgentResult.of(PUBLISH, JSON.writeValueAsString(json));
    }
}
