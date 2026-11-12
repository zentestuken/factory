package org.folio.factory.autotests.worker;

import org.folio.factory.autotests.workspace.AutomationWorkspace;
import org.folio.factory.autotests.workspace.PreCommitChecks;
import org.folio.factory.autotests.workspace.RunEnvironments;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.ArtifactContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automates the selected cases one after another on one branch: explore, then write the spec,
 * then review it - Ostap's three workers, unchanged in what they do. One case failing is recorded
 * and the loop goes on; only workspace failures fail the step, so a flow-level retry never
 * replays paid sessions for cases that already finished. Everything is committed locally at the
 * end; nothing is pushed.
 */
public class AutomateCasesAgentWorker implements AgentWorker {

    public static final String ID = "cypress-automate-cases";
    public static final String REPORT = "automation_report.md";
    public static final String RESULTS = "automation_results.json";
    private static final Logger log = LoggerFactory.getLogger(AutomateCasesAgentWorker.class);
    private static final JsonMapper JSON = new JsonMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    private final CypressTestFrameworkExplorerAgentWorker explorer;
    private final CypressTestAutomationGenerationAgentWorker generator;
    private final CypressTestAutomationReviewAgentWorker reviewer;
    private final AutomationWorkspace workspace;
    private final PreCommitChecks checks;
    /** Null when specs are not run. */
    private final RunEnvironments environments;
    private final boolean runSpecs;

    public AutomateCasesAgentWorker(CypressTestFrameworkExplorerAgentWorker explorer,
                                    CypressTestAutomationGenerationAgentWorker generator,
                                    CypressTestAutomationReviewAgentWorker reviewer,
                                    AutomationWorkspace workspace, PreCommitChecks checks,
                                    RunEnvironments environments) {
        this.explorer = explorer;
        this.generator = generator;
        this.reviewer = reviewer;
        this.workspace = workspace;
        this.checks = checks;
        this.environments = environments;
        this.runSpecs = environments != null;
    }

    @Override
    public String id() {
        return ID;
    }

    static final String LINT_CLEAN = "clean";
    static final String LINT_NOT_RUN = "not run: ";

    /**
     * One case's outcome; {@code error} is set only when the case failed. {@code lint} is
     * {@link #LINT_CLEAN}, {@code "not run: <why>"}, or the errors the pre-commit checks left.
     */
    record CaseResult(String caseId, String title, String runEnvironment, String testFile, String verdict, Boolean ran,
                      List<String> findings, String generationSummary, String reviewSummary, String error,
                      String lint) {

        CaseResult withTestFile(String testFile) {
            return new CaseResult(caseId, title, runEnvironment, testFile, verdict, ran, findings,
                    generationSummary, reviewSummary, error, lint);
        }

        CaseResult withLint(String lint) {
            return new CaseResult(caseId, title, runEnvironment, testFile, verdict, ran, findings,
                    generationSummary, reviewSummary, error, lint);
        }

        boolean hasLintErrors() {
            return lint != null && !lint.equals(LINT_CLEAN) && !lint.startsWith(LINT_NOT_RUN);
        }
    }

    @Override
    public AgentResult execute(AgentContext context) {
        JsonNode selection = JSON.readTree(context.requireInput(AutomationSelectAgentWorker.SELECTION).content());
        ArtifactContent feedbackArtifact = context.inputs().get("hitl_feedback.md");
        String feedback = feedbackArtifact == null ? "" : feedbackArtifact.content();
        List<JsonNode> cases = new ArrayList<>();
        selection.path("cases").forEach(cases::add);

        if (cases.isEmpty()) {
            String note = selection.path("note").asString("Nothing to automate");
            return outputs(report(selection, note, null, "", List.of()), List.of(), null, null);
        }

        List<String> areas = new ArrayList<>();
        selection.path("areas").forEach(a -> areas.add(a.asString("")));
        String branch = branchName(selection, cases);
        List<CaseResult> results = new ArrayList<>();
        String issueKey = selection.path("issueKey").asString("");
        BranchState state = workspace.onBranch(branch, root -> {
            String checksUnavailable = checks.prepare(workspace);
            if (!checksUnavailable.isBlank()) {
                log.warn("[automation] Pre-commit checks (lint-staged) will not run: {}", checksUnavailable);
            }
            if (runSpecs) workspace.ensureDependencies();
            for (int i = 0; i < cases.size(); i++) {
                JsonNode c = cases.get(i);
                log.info("[{}] {} ({}/{})", ID, c.path("caseId").asString(""), i + 1, cases.size());
                CaseResult result = automateOne(c, areas, feedback);
                // One commit per case: the next case's reviewer diffs against HEAD, and must see
                // only its own case's changes. A failed case leaves nothing half-done behind.
                if (result.error() == null) {
                    result = result.withLint(lint(checksUnavailable));
                    workspace.commitAll(commitMessage(result.caseId(), issueKey));
                    if (result.testFile().isBlank()) {
                        // The reviewer's JSON has no fixed key for the spec path; the commit is the truth.
                        result = result.withTestFile(String.join(", ", workspace.lastCommitSpecs()));
                    }
                } else {
                    workspace.discardChanges();
                }
                results.add(result);
            }
            return new BranchState(workspace.branchChanges(), workspace.baseSha(), workspace.headSha());
        });
        long failed = results.stream().filter(r -> r.error() != null).count();
        log.info("[automation] Finished: {} case(s) automated, {} failed, {} skipped, on branch {} - gate 2 is next",
                results.size() - failed, failed, selection.path("skipped").size(), branch);
        return outputs(report(selection, null, branch, state.changes(), results), results, branch, state);
    }

    private String lint(String checksUnavailable) {
        if (!checksUnavailable.isBlank()) return LINT_NOT_RUN + checksUnavailable;
        try {
            String remaining = checks.run(workspace);
            return remaining.isBlank() ? LINT_CLEAN : remaining;
        } catch (RuntimeException e) {
            return LINT_NOT_RUN + e.getMessage();
        }
    }

    private CaseResult automateOne(JsonNode c, List<String> areas, String feedback) {
        String caseId = c.path("caseId").asString("");
        String title = c.path("title").asString("");
        String profile = runSpecs ? RunEnvironments.profileFor(c.path("details")) : null;
        String generation = "";
        String review = "";
        try {
            JsonNode testCase = c.path("details");
            JsonNode related = explorer.explore(testCase, areas);
            List<String> guidance = new ArrayList<>();
            String team = c.path("team").asString("");
            if (!team.isBlank()) {
                guidance.add("Dev team of this test case: " + team + " - use it, lowercased, as the spec's dev team tag "
                        + "(not the team of neighbouring specs).");
            }
            String notes = c.path("automationNotes").asString("");
            if (!notes.isBlank()) guidance.add("Automation notes from the test case author: " + notes);
            if (!feedback.isBlank()) {
                guidance.add("Reviewer feedback from gate 2 on the previous attempt - address every point:\n" + feedback);
            }
            generation = generator.automate(testCase, related, String.join("\n\n", guidance));
            if (runSpecs) {
                workspace.writeIgnoredFile("environments.js", environments.environmentsJs(profile));
            }
            review = reviewer.review(testCase, related, generation);
            JsonNode verdict = verdictBlock(review);
            List<String> findings = new ArrayList<>();
            verdict.path("findings").forEach(f -> findings.add(f.path("priority").asString("") + ": "
                    + firstText(f, "summary", "issue", "description", "title")
                    + (f.path("fixed").asBoolean(false) ? " (fixed)" : "")));
            return new CaseResult(caseId, title, profile, firstText(verdict, "test_file", "spec", "file", "test_file_path"),
                    verdict.path("verdict").asString("unknown"),
                    verdict.has("ran") ? verdict.path("ran").asBoolean(false) : null,
                    findings, generation, review, null, null);
        } catch (RuntimeException e) {
            log.warn("[{}] {} failed: {}", ID, caseId, e.getMessage());
            return new CaseResult(caseId, title, profile, "", "FAILED", null, List.of(), generation, review,
                    e.getMessage(), null);
        }
    }

    /** The reviewer ends with a fenced JSON verdict; the last such block wins. Empty when absent. */
    static JsonNode verdictBlock(String review) {
        Matcher m = JSON_BLOCK.matcher(review == null ? "" : review);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last == null) return JSON.createObjectNode();
        try {
            return JSON.readTree(last);
        } catch (RuntimeException e) {
            return JSON.createObjectNode();
        }
    }

    /** The review prompt doesn't fix the verdict's key names, so the model's wording varies between runs. */
    static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = node.path(key).asString("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    static String branchName(JsonNode selection, List<JsonNode> cases) {
        String key = selection.path("issueKey").asString("");
        String base = key.isBlank() ? cases.getFirst().path("caseId").asString("cases") : key;
        return "factory/" + base.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String commitMessage(String caseId, String issueKey) {
        return "Automate " + caseId + (issueKey.isBlank() ? "" : " (" + issueKey + ")")
                + " - generated by the FOLIO AI factory";
    }

    /** The run's branch as committed: the publish step reads files from these commits, not the working tree. */
    record BranchState(String changes, String baseSha, String headSha) {}

    private AgentResult outputs(String report, List<CaseResult> results, String branch, BranchState state) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("branch", branch);
        json.put("baseSha", state == null ? null : state.baseSha());
        json.put("headSha", state == null ? null : state.headSha());
        json.put("workspace", branch == null ? null : workspace.root().toString());
        json.put("cases", results);
        return new AgentResult(Map.of(REPORT, report, RESULTS, JSON.writeValueAsString(json)), Map.of());
    }

    private String report(JsonNode selection, String note, String branch, String changes, List<CaseResult> results) {
        StringBuilder md = new StringBuilder("# Automation report\n\n");
        if (note != null) {
            md.append("**").append(note).append(".** Nothing was generated; approve to finish the run.\n");
        } else {
            md.append("- **Branch:** `").append(branch).append("`, committed locally in `")
                    .append(workspace.root()).append("` (pushed to the target repo only when gate 2 is approved)\n")
                    .append("- **See the diff (one commit per case):** `git -C \"").append(workspace.root())
                    .append("\" log -p origin/master..HEAD`\n")
                    .append("- **Spec runs:** ").append(runSpecs ? "enabled"
                            : "disabled (FACTORY_CYPRESS_AUTOMATION_RUN_SPECS=false) - specs were reviewed, not run")
                    .append("\n");
            List<String> linty = results.stream().filter(CaseResult::hasLintErrors).map(CaseResult::caseId).toList();
            if (!linty.isEmpty()) {
                md.append("\n> **⚠ Lint errors remain in ").append(String.join(", ", linty))
                        .append("** - prettier and stripes-lint --fix ran (the TAF pre-commit hook); "
                                + "what they could not fix is listed per case below.\n");
            }
            md.append("\n## Changed files\n\n```\n")
                    .append(changes.isBlank() ? "(no changes)" : changes).append("\n```\n");
            for (CaseResult r : results) {
                md.append("\n## ").append(r.caseId()).append(" - ").append(r.title()).append("\n\n");
                if (r.error() != null) {
                    md.append("- **Failed:** ").append(r.error()).append(" - its changes were discarded\n");
                } else {
                    md.append("- **Spec:** `").append(r.testFile().isBlank() ? "(not reported)" : r.testFile()).append("`\n")
                            .append("- **Review verdict:** ").append(r.verdict())
                            .append(r.ran() == null ? "" : r.ran() ? " (spec was run)" : " (spec not run)").append('\n');
                    if (r.lint() != null) {
                        if (r.lint().equals(LINT_CLEAN)) {
                            md.append("- **Lint:** clean (prettier + stripes-lint --fix, as the TAF pre-commit hook)\n");
                        } else if (r.lint().startsWith(LINT_NOT_RUN)) {
                            md.append("- **Lint:** ").append(r.lint()).append('\n');
                        } else {
                            md.append("- **Lint: ⚠ errors remain** after the automatic fixes:\n\n```\n")
                                    .append(r.lint()).append("\n```\n");
                        }
                    }
                    if (r.runEnvironment() != null) {
                        md.append("- **Run environment:** ").append(r.runEnvironment()).append(" (")
                                .append(environments.baseUrl(r.runEnvironment())).append(")\n");
                    }
                    if (!r.findings().isEmpty()) {
                        md.append("- **Findings:**\n");
                        r.findings().forEach(f -> md.append("  - ").append(f).append('\n'));
                    }
                }
                if (!r.generationSummary().isBlank()) {
                    md.append("\n### Generation summary\n\n").append(r.generationSummary()).append('\n');
                }
                if (!r.reviewSummary().isBlank()) {
                    md.append("\n### Review summary\n\n").append(r.reviewSummary()).append('\n');
                }
            }
        }
        List<String> skipped = new ArrayList<>();
        selection.path("skipped").forEach(s -> skipped.add(s.path("caseId").asString("") + " - "
                + s.path("reason").asString("")));
        if (!skipped.isEmpty()) {
            md.append("\n## Skipped\n\n");
            skipped.forEach(s -> md.append("- ").append(s).append('\n'));
        }
        return md.toString();
    }
}
