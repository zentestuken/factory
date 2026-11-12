package org.folio.factory.autotests.worker;

import org.folio.factory.autotests.TestAutomationFlowProperties;
import org.folio.factory.autotests.workspace.AutomationWorkspace;
import org.folio.factory.autotests.workspace.AutomationWorkspaceTest;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.service.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AutomationPublishAgentWorkerTest {

    private static final JsonMapper JSON = new JsonMapper();
    private static final UUID EXECUTION = UUID.fromString("abcd1234-0000-0000-0000-000000000000");
    private static final byte[] MARC = {'0', '0', '1', 0x1E, (byte) 0xC3, (byte) 0x28, 0x1D, 0x00, 'x'};

    @TempDir
    Path tmp;

    static class FakeGitHub implements GitHubConnector {
        final List<String> calls = new ArrayList<>();
        String prBody = "";
        String openPr = null;
        boolean failPr = false;

        @Override public void createBranch(String repo, String base, String branch) { calls.add("createBranch"); }
        @Override public void syncFork(String repo, String branch) { calls.add("syncFork " + repo + " " + branch); }
        @Override public void commitFiles(String repo, String branch, Map<String, String> files, String message) {
            calls.add("commitFiles");
        }
        @Override public String createPullRequest(String repo, String head, String base, String title, String body) {
            if (failPr) throw new RestClientException("403 Resource not accessible by integration");
            prBody = body;
            calls.add("createPullRequest " + head + " -> " + base + " " + title);
            return "https://github.com/" + repo + "/pull/7";
        }
        @Override public Optional<String> findOpenPullRequest(String repo, String head, String base) {
            return Optional.ofNullable(openPr);
        }
        @Override public String getFileContent(String repo, String branch, String path) { return ""; }
        @Override public List<String> listDirectory(String repo, String branch, String path) { return List.of(); }
    }

    private static TestAutomationFlowProperties props(String targetRepo) {
        return new TestAutomationFlowProperties(targetRepo, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private record Run(AutomationWorkspace workspace, String baseSha, String headSha, Path fork) {}

    /** Two case commits: a new spec plus a binary fixture, then an edit plus a deletion. */
    private Run run() throws Exception {
        Path origin = AutomationWorkspaceTest.originRepo(tmp.resolve("origin"));
        Path fork = tmp.resolve("hosts/me/fork.git");
        Files.createDirectories(fork.getParent());
        AutomationWorkspaceTest.git(tmp, "clone", "-q", "--bare", origin.toUri().toString(), fork.toString());
        AutomationWorkspace workspace = AutomationWorkspaceTest.workspace(tmp.resolve("work"), origin);
        String[] shas = workspace.onBranch("factory/UIOR-1", root -> {
            try {
                Files.writeString(root.resolve("cypress/e2e/new.cy.js"), "it('C1')");
                Files.createDirectories(root.resolve("cypress/fixtures"));
                Files.write(root.resolve("cypress/fixtures/record.mrc"), MARC);
                workspace.commitAll("Automate C1");
                Files.writeString(root.resolve("cypress/e2e/existing.cy.js"), "it('existing, improved')");
                Files.delete(root.resolve(".gitignore"));
                workspace.commitAll("Automate C2");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new String[] {workspace.baseSha(), workspace.headSha()};
        });
        return new Run(workspace, shas[0], shas[1], fork);
    }

    private static AgentContext context(Run run, String casesJson) {
        String results = "{\"branch\":\"factory/UIOR-1\",\"baseSha\":\"" + run.baseSha() + "\",\"headSha\":\""
                + run.headSha() + "\",\"cases\":" + casesJson + "}";
        Map<String, ArtifactContent> inputs = Map.of(
                AutomateCasesAgentWorker.RESULTS, new ArtifactContent(AutomateCasesAgentWorker.RESULTS, 1, "application/json", results),
                AutomationSelectAgentWorker.SELECTION, new ArtifactContent(AutomationSelectAgentWorker.SELECTION, 1,
                        "application/json", "{\"issueKey\":\"UIOR-1\"}"));
        return new AgentContext(EXECUTION, "publish", inputs, null, Map.of(), List.of(AutomationPublishAgentWorker.PUBLISH));
    }

    private static final String TWO_DONE = """
            [{"caseId":"C1","title":"First","runEnvironment":"non-ecs","testFile":"cypress/e2e/new.cy.js",
              "verdict":"PASS","ran":true,"findings":["nit: rename var (fixed)"],"error":null},
             {"caseId":"C2","title":"Second","runEnvironment":null,"testFile":"cypress/e2e/existing.cy.js",
              "verdict":"NEEDS_RERUN","ran":false,"findings":[],"error":null}]
            """;

    private JsonNode publish(FakeGitHub gitHub, Run run, String targetRepo, String token, String cases) {
        String host = run.fork().getParent().getParent().toUri().toString();
        var worker = new AutomationPublishAgentWorker(gitHub, run.workspace(), props(targetRepo),
                "https://foliotest.testrail.io/", token, host, mock(AuditLog.class));
        return JSON.readTree(worker.execute(context(run, cases)).outputs().get(AutomationPublishAgentWorker.PUBLISH));
    }

    @Test
    void pushesTheRunsCommitsAsTheyAreAndOpensThePr() throws Exception {
        Run run = run();
        FakeGitHub gitHub = new FakeGitHub();

        JsonNode out = publish(gitHub, run, "me/fork", "t0ken", TWO_DONE);

        assertThat(out.path("status").asString("")).isEqualTo("done");
        assertThat(out.path("prUrl").asString("")).isEqualTo("https://github.com/me/fork/pull/7");
        assertThat(gitHub.calls).containsExactly("syncFork me/fork master",
                "createPullRequest factory/UIOR-1-abcd1234 -> master [UIOR-1] Automate C1, C2");
        String pushed = "factory/UIOR-1-abcd1234";
        assertThat(AutomationWorkspaceTest.git(run.fork(), "rev-parse", pushed).strip()).isEqualTo(run.headSha());
        assertThat(AutomationWorkspaceTest.git(run.fork(), "log", "--format=%s", "master.." + pushed))
                .contains("Automate C1", "Automate C2");
        assertThat(AutomationWorkspaceTest.git(run.fork(), "diff", "--name-status", "master", pushed))
                .contains("D\t.gitignore", "A\tcypress/fixtures/record.mrc", "M\tcypress/e2e/existing.cy.js");
        Path check = tmp.resolve("check");
        AutomationWorkspaceTest.git(tmp, "clone", "-q", "--branch", pushed, run.fork().toUri().toString(), check.toString());
        assertThat(Files.readAllBytes(check.resolve("cypress/fixtures/record.mrc"))).isEqualTo(MARC);
        assertThat(gitHub.prBody).contains("[C1](https://foliotest.testrail.io/index.php?/cases/view/1) First",
                "PASS (run on non-ecs)", "NEEDS_RERUN (not run)", "nit: rename var (fixed)", "One commit per case",
                "execution `abcd1234-0000-0000-0000-000000000000`", run.baseSha());
    }

    @Test
    void withoutATargetRepoOrTokenNothingIsSent() throws Exception {
        Run run = run();
        FakeGitHub gitHub = new FakeGitHub();

        JsonNode noRepo = publish(gitHub, run, "", "t0ken", TWO_DONE);
        JsonNode noToken = publish(gitHub, run, "me/fork", "", TWO_DONE);

        assertThat(noRepo.path("detail").asString("")).contains("FACTORY_CYPRESS_TARGET_REPO");
        assertThat(noToken.path("detail").asString("")).contains("FACTORY_CONNECTORS_GITHUB_TOKEN");
        assertThat(gitHub.calls).isEmpty();
    }

    @Test
    void nothingIsPublishedWhenEveryCaseFailed() throws Exception {
        Run run = run();
        FakeGitHub gitHub = new FakeGitHub();

        JsonNode out = publish(gitHub, run, "me/fork", "t0ken",
                "[{\"caseId\":\"C1\",\"title\":\"x\",\"error\":\"claude CLI timed out\"}]");

        assertThat(out.path("detail").asString("")).contains("no case was automated");
        assertThat(gitHub.calls).isEmpty();
    }

    @Test
    void anAlreadyOpenPrIsReusedAndErrorsAreReportedNotThrown() throws Exception {
        Run run = run();
        FakeGitHub reopened = new FakeGitHub();
        reopened.openPr = "https://github.com/me/fork/pull/3";
        FakeGitHub forbidden = new FakeGitHub();
        forbidden.failPr = true;

        JsonNode again = publish(reopened, run, "me/fork", "t0ken", TWO_DONE);
        JsonNode failed = publish(forbidden, run, "me/fork", "t0ken", TWO_DONE);
        JsonNode badRemote = publish(new FakeGitHub(), run, "nobody/missing", "t0ken", TWO_DONE);

        assertThat(again.path("prUrl").asString("")).endsWith("/pull/3");
        assertThat(reopened.calls).isEmpty();
        assertThat(failed.path("status").asString("")).isEqualTo("failed");
        assertThat(failed.path("detail").asString("")).contains("403");
        assertThat(badRemote.path("status").asString("")).isEqualTo("failed");
        assertThat(badRemote.path("detail").asString("")).contains("git push");
    }

    @Test
    void mapsTheGitHubApiUrlToItsGitHost() {
        assertThat(AutomationPublishAgentWorker.gitHostFor("https://api.github.com")).isEqualTo("https://github.com/");
        assertThat(AutomationPublishAgentWorker.gitHostFor("https://ghe.example.org/api/v3/"))
                .isEqualTo("https://ghe.example.org/");
    }
}
