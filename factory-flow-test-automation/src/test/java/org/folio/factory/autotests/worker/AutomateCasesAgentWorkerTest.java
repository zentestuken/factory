package org.folio.factory.autotests.worker;

import org.folio.factory.autotests.cli.CodingAgentCli;
import org.folio.factory.autotests.workspace.AutomationWorkspace;
import org.folio.factory.autotests.workspace.RunEnvironments;
import org.folio.factory.autotests.workspace.AutomationWorkspaceTest;
import org.folio.factory.autotests.workspace.PreCommitChecks;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AutomateCasesAgentWorkerTest {

    private static final JsonMapper JSON = new JsonMapper();

    @TempDir
    Path tmp;

    /** Pre-commit checks that always pass, so tests never need node_modules. */
    static final PreCommitChecks CLEAN = new PreCommitChecks() {
        @Override public String prepare(AutomationWorkspace workspace) { return ""; }
        @Override public String run(AutomationWorkspace workspace) { return ""; }
    };

    /** Writes a spec named after the case when generating; answers with a verdict block when reviewing. */
    static class FakeCli implements CodingAgentCli {
        final List<String> prompts = new ArrayList<>();
        final List<List<String>> tools = new ArrayList<>();

        @Override
        public String run(String prompt, Path workDir, List<String> allowedTools) {
            prompts.add(prompt);
            tools.add(allowedTools);
            if (prompt.contains("FAIL-ME") && !allowedTools.contains("Bash(git status:*)")) {
                throw new IllegalStateException("claude CLI exited with code 1: boom");
            }
            if (!allowedTools.contains("Bash(git status:*)")) {
                String caseId = prompt.contains("Your test is for case C1") ? "C1" : "C2";
                try {
                    Files.writeString(workDir.resolve("cypress/e2e/" + caseId + ".cy.js"), "it('" + caseId + "')");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return "Wrote cypress/e2e/" + caseId + ".cy.js";
            }
            return """
                    ## Verdict
                    PASS
                    ```json
                    {"verdict":"PASS","ran":false,"test_file":"cypress/e2e/C1.cy.js",
                     "findings":[{"priority":"nit","summary":"rename var","fixed":true}]}
                    ```
                    """;
        }
    }

    private AutomateCasesAgentWorker worker(FakeCli cli, AutomationWorkspace workspace) {
        var explorer = new CypressTestFrameworkExplorerAgentWorker(null, workspace) {
            @Override
            public JsonNode explore(JsonNode testCase, List<String> areas) {
                return JSON.readTree("{\"similar_tests\":[],\"support_files\":[]}");
            }
        };
        return new AutomateCasesAgentWorker(explorer,
                new CypressTestAutomationGenerationAgentWorker(null, cli, workspace),
                new CypressTestAutomationReviewAgentWorker(null, cli, workspace, false),
                workspace, CLEAN, null);
    }

    private static AgentContext context(String selection) {
        Map<String, ArtifactContent> inputs = new HashMap<>();
        inputs.put("automation_cases.json", new ArtifactContent("automation_cases.json", 1, "application/json", selection));
        return new AgentContext(UUID.randomUUID(), "automate", inputs, null, Map.of(),
                List.of(AutomateCasesAgentWorker.REPORT, AutomateCasesAgentWorker.RESULTS));
    }

    @Test
    void oneFailingCaseDoesNotStopTheOthersAndTheRestIsCommittedOnTheRunBranch() throws Exception {
        Path origin = AutomationWorkspaceTest.originRepo(tmp.resolve("origin"));
        AutomationWorkspace workspace = AutomationWorkspaceTest.workspace(tmp.resolve("work"), origin);
        FakeCli cli = new FakeCli();

        AgentResult result = worker(cli, workspace).execute(context("""
                {"issueKey":"UIU-1","areas":["eureka"],"cases":[
                  {"caseId":"C1","title":"First","automationNotes":"reuse usersCard.js","team":"Dresden","details":{"id":1,"title":"First"}},
                  {"caseId":"C2","title":"Second FAIL-ME","automationNotes":"","details":{"id":2,"title":"Second FAIL-ME"}}],
                 "skipped":[{"caseId":"C3","reason":"marked Automatable: No"}]}
                """));

        String report = result.outputs().get(AutomateCasesAgentWorker.REPORT);
        assertThat(report).contains("`factory/UIU-1`", "cypress/e2e/C1.cy.js", "Review verdict:** PASS (spec not run)",
                "nit: rename var (fixed)", "## C2 - Second FAIL-ME", "**Failed:** claude CLI exited with code 1",
                "C3 - marked Automatable: No", "disabled (FACTORY_CYPRESS_AUTOMATION_RUN_SPECS=false)");
        assertThat(AutomationWorkspaceTest.git(workspace.root(), "log", "--format=%s", "origin/master..HEAD").strip())
                .isEqualTo("Automate C1 (UIU-1) - generated by the FOLIO AI factory");
        assertThat(AutomationWorkspaceTest.git(workspace.root(), "rev-parse", "--abbrev-ref", "HEAD").strip())
                .isEqualTo("factory/UIU-1");
        assertThat(Files.exists(workspace.root().resolve("cypress/e2e/C2.cy.js"))).isFalse();
        assertThat(report).contains("its changes were discarded", "log -p origin/master..HEAD");
        assertThat(cli.prompts.getFirst()).contains("Automation notes from the test case author: reuse usersCard.js",
                "Dev team of this test case: Dresden - use it, lowercased, as the spec's dev team tag",
                "Your test is for case C1").doesNotContain("{{");
        assertThat(cli.tools.get(1)).doesNotContain("Bash(npx cypress run:*)");
        JsonNode results = JSON.readTree(result.outputs().get(AutomateCasesAgentWorker.RESULTS));
        assertThat(results.path("cases").get(1).path("error").asString("")).contains("boom");
    }

    @Test
    void eachCaseGetsItsOwnCommitSoTheNextReviewSeesOnlyItsOwnChanges() throws Exception {
        Path origin = AutomationWorkspaceTest.originRepo(tmp.resolve("origin"));
        AutomationWorkspace workspace = AutomationWorkspaceTest.workspace(tmp.resolve("work"), origin);
        List<String> uncommittedAtReview = new ArrayList<>();
        FakeCli cli = new FakeCli() {
            @Override
            public String run(String prompt, Path workDir, List<String> allowedTools) {
                if (allowedTools.contains("Bash(git status:*)")) {
                    try {
                        uncommittedAtReview.add(AutomationWorkspaceTest.git(workDir, "status", "--porcelain").strip());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return super.run(prompt, workDir, allowedTools);
            }
        };

        AgentResult result = worker(cli, workspace).execute(context("""
                {"issueKey":"","cases":[
                  {"caseId":"C1","title":"First","details":{"id":1,"title":"First"}},
                  {"caseId":"C2","title":"Second","details":{"id":2,"title":"Second"}}]}
                """));

        assertThat(uncommittedAtReview).containsExactly("?? cypress/e2e/C1.cy.js", "?? cypress/e2e/C2.cy.js");
        assertThat(AutomationWorkspaceTest.git(workspace.root(), "log", "--format=%s", "origin/master..HEAD"))
                .contains("Automate C2 - generated", "Automate C1 - generated");
        assertThat(result.outputs().get(AutomateCasesAgentWorker.REPORT))
                .contains("`factory/C1`", "C1.cy.js", "C2.cy.js");
    }

    @Test
    void whenTheReviewerNamesNoSpecTheReportTakesItFromTheCaseCommit() throws Exception {
        Path origin = AutomationWorkspaceTest.originRepo(tmp.resolve("origin"));
        AutomationWorkspace workspace = AutomationWorkspaceTest.workspace(tmp.resolve("work"), origin);
        FakeCli cli = new FakeCli() {
            @Override
            public String run(String prompt, Path workDir, List<String> allowedTools) {
                String out = super.run(prompt, workDir, allowedTools);
                return allowedTools.contains("Bash(git status:*)")
                        ? "```json\n{\"verdict\":\"NEEDS_RERUN\",\"ran\":false}\n```" : out;
            }
        };

        String report = worker(cli, workspace).execute(context(
                "{\"issueKey\":\"UIU-1\",\"cases\":[{\"caseId\":\"C2\",\"title\":\"Second\",\"details\":{\"id\":2}}]}"))
                .outputs().get(AutomateCasesAgentWorker.REPORT);

        assertThat(report).contains("- **Spec:** `cypress/e2e/C2.cy.js`").doesNotContain("(not reported)");
    }

    @Test
    void anEmptySelectionReportsWhyAndNeverTouchesTheWorkspace() {
        AutomationWorkspace workspace = new AutomationWorkspace(tmp.resolve("never-created"));

        AgentResult result = worker(new FakeCli(), workspace).execute(context(
                "{\"issueKey\":\"UIU-1\",\"cases\":[],\"skipped\":[],\"note\":\"Automation disabled for this run\"}"));

        assertThat(result.outputs().get(AutomateCasesAgentWorker.REPORT))
                .contains("**Automation disabled for this run.** Nothing was generated; approve to finish the run.");
        assertThat(Files.exists(tmp.resolve("never-created"))).isFalse();
    }

    @Test
    void readsTheLastJsonVerdictBlockAndToleratesItsAbsence() {
        String review = "```json\n{\"verdict\":\"OLD\"}\n```\ntext\n```json\n{\"verdict\":\"NEEDS_RERUN\",\"ran\":false}\n```";

        assertThat(AutomateCasesAgentWorker.verdictBlock(review).path("verdict").asString("")).isEqualTo("NEEDS_RERUN");
        assertThat(AutomateCasesAgentWorker.verdictBlock("no block").isEmpty()).isTrue();
    }

    @Test
    void readsTheSpecAndFindingTextUnderEitherKeyTheModelHasUsed() {
        JsonNode yesterday = JSON.readTree("{\"test_file\":\"a.cy.js\",\"findings\":[{\"summary\":\"race\"}]}");
        JsonNode today = JSON.readTree("{\"spec\":\"b.cy.js\",\"findings\":[{\"issue\":\"comment format\"}]}");

        assertThat(AutomateCasesAgentWorker.firstText(yesterday, "test_file", "spec")).isEqualTo("a.cy.js");
        assertThat(AutomateCasesAgentWorker.firstText(today, "test_file", "spec")).isEqualTo("b.cy.js");
        assertThat(AutomateCasesAgentWorker.firstText(today.path("findings").get(0), "summary", "issue"))
                .isEqualTo("comment format");
    }

    @Test
    void withSpecRunsEachCaseRunsOnTheEnvironmentMatchingItsEcsFlag() throws Exception {
        Path origin = AutomationWorkspaceTest.originRepo(tmp.resolve("origin"));
        AutomationWorkspace workspace = AutomationWorkspaceTest.workspace(tmp.resolve("work"), origin);
        workspace.onBranch("init", root -> root.resolve("node_modules").toFile().mkdirs());
        List<String> activeAtReview = new ArrayList<>();
        FakeCli cli = new FakeCli() {
            @Override
            public String run(String prompt, Path workDir, List<String> allowedTools) {
                if (allowedTools.contains("Bash(git status:*)")) {
                    try {
                        String js = Files.readString(workDir.resolve("environments.js"));
                        activeAtReview.add(js.lines().filter(l -> l.startsWith("const activeEnvironment"))
                                .findFirst().orElse(""));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return super.run(prompt, workDir, allowedTools);
            }
        };
        var explorer = new CypressTestFrameworkExplorerAgentWorker(null, workspace) {
            @Override
            public JsonNode explore(JsonNode testCase, List<String> areas) {
                return JSON.readTree("{}");
            }
        };
        var worker = new AutomateCasesAgentWorker(explorer,
                new CypressTestAutomationGenerationAgentWorker(null, cli, workspace),
                new CypressTestAutomationReviewAgentWorker(null, cli, workspace, true),
                workspace, CLEAN, RunEnvironments.load(""));

        AgentResult result = worker.execute(context("""
                {"issueKey":"UIU-1","cases":[
                  {"caseId":"C1","title":"Plain","details":{"id":1,"custom_ecs_enabled":false}},
                  {"caseId":"C2","title":"Consortia","details":{"id":2,"custom_ecs_enabled":true}}]}
                """));

        assertThat(activeAtReview).containsExactly("const activeEnvironment = \"non-ecs\";",
                "const activeEnvironment = \"ecs\";");
        assertThat(result.outputs().get(AutomateCasesAgentWorker.REPORT))
                .contains("Run environment:** non-ecs (https://folio-etesting-snapshot-diku.ci.folio.org)",
                        "Run environment:** ecs (https://folio-etesting-snapshot-consortium.ci.folio.org)");
        assertThat(AutomationWorkspaceTest.git(workspace.root(), "show", "--stat", "--format=", "HEAD"))
                .doesNotContain("environments.js");
    }

    @Test
    void preCommitChecksRunBeforeEachCommitAndWhatTheyLeaveIsReported() throws Exception {
        Path origin = AutomationWorkspaceTest.originRepo(tmp.resolve("origin"));
        AutomationWorkspace workspace = AutomationWorkspaceTest.workspace(tmp.resolve("work"), origin);
        List<String> sawUncommitted = new ArrayList<>();
        PreCommitChecks lint = new PreCommitChecks() {
            @Override public String prepare(AutomationWorkspace ws) { return ""; }
            @Override public String run(AutomationWorkspace ws) {
                try {
                    String status = AutomationWorkspaceTest.git(ws.root(), "status", "--porcelain").strip();
                    sawUncommitted.add(status);
                    return status.contains("C2") ? "C2.cy.js  3:7  error  'x' is assigned a value but never used" : "";
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        var explorer = new CypressTestFrameworkExplorerAgentWorker(null, workspace) {
            @Override
            public JsonNode explore(JsonNode testCase, List<String> areas) {
                return JSON.readTree("{}");
            }
        };
        FakeCli cli = new FakeCli();
        var worker = new AutomateCasesAgentWorker(explorer,
                new CypressTestAutomationGenerationAgentWorker(null, cli, workspace),
                new CypressTestAutomationReviewAgentWorker(null, cli, workspace, false), workspace, lint, null);

        String report = worker.execute(context("""
                {"issueKey":"UIU-1","cases":[
                  {"caseId":"C1","title":"Clean","details":{"id":1}},
                  {"caseId":"C2","title":"Linty","details":{"id":2}}]}
                """)).outputs().get(AutomateCasesAgentWorker.REPORT);

        assertThat(sawUncommitted).containsExactly("?? cypress/e2e/C1.cy.js", "?? cypress/e2e/C2.cy.js");
        assertThat(report).contains("**⚠ Lint errors remain in C2**",
                "- **Lint:** clean (prettier + stripes-lint --fix, as the TAF pre-commit hook)",
                "- **Lint: ⚠ errors remain** after the automatic fixes", "'x' is assigned a value but never used");
        assertThat(AutomationWorkspaceTest.git(workspace.root(), "log", "--format=%s", "origin/master..HEAD"))
                .contains("Automate C1", "Automate C2");
    }

    @Test
    void whenTheChecksCannotRunTheCasesStillCommitAndTheReportSaysWhy() throws Exception {
        Path origin = AutomationWorkspaceTest.originRepo(tmp.resolve("origin"));
        AutomationWorkspace workspace = AutomationWorkspaceTest.workspace(tmp.resolve("work"), origin);
        PreCommitChecks unavailable = new PreCommitChecks() {
            @Override public String prepare(AutomationWorkspace ws) { return "dependencies could not be installed: yarn not found"; }
            @Override public String run(AutomationWorkspace ws) { throw new AssertionError("must not run"); }
        };
        var explorer = new CypressTestFrameworkExplorerAgentWorker(null, workspace) {
            @Override
            public JsonNode explore(JsonNode testCase, List<String> areas) {
                return JSON.readTree("{}");
            }
        };
        FakeCli cli = new FakeCli();
        var worker = new AutomateCasesAgentWorker(explorer,
                new CypressTestAutomationGenerationAgentWorker(null, cli, workspace),
                new CypressTestAutomationReviewAgentWorker(null, cli, workspace, false), workspace, unavailable, null);

        String report = worker.execute(context(
                "{\"issueKey\":\"UIU-1\",\"cases\":[{\"caseId\":\"C1\",\"title\":\"x\",\"details\":{\"id\":1}}]}"))
                .outputs().get(AutomateCasesAgentWorker.REPORT);

        assertThat(report).contains("- **Lint:** not run: dependencies could not be installed: yarn not found");
        assertThat(AutomationWorkspaceTest.git(workspace.root(), "log", "--format=%s", "origin/master..HEAD"))
                .contains("Automate C1");
    }
}
