# Cypress Factory Flow — Implementation Plan

**Story → Manual Test Cases → Cypress E2E specs, using TAF AI assets and MQA skill assets**

This is the single plan for the flow. It covers the test-case half (Part I), the shared reference
material both halves need (Part II), and the Cypress-automation half (Part III). Both halves are
built and run as one flow, `cypress-factory`. How to run it is in [QUICKSTART.md](QUICKSTART.md).

---

## Status at a glance

| | Scope | State |
|---|---|---|
| **Part I** | Story → manual test cases → TestRail, behind QA gate 1 | Implemented. Steps 0-2. |
| **Part II** | Asset inventories, roles, env vars, reuse | Reference for both halves. |
| **Part III** | TestRail cases → Cypress specs (one commit per case) → gate 2 → PR on a fork | Implemented (2026-11-12). Steps 3-6. Spec runs are implemented but not yet verified end to end. |

---

# Part I — Story → Manual Test Cases (built)

**Approach:** lossless prefetch + a single mega-call, markdown in and markdown out.
The two alternatives that were considered and rejected are in Appendix A.

---

## 1. Design principle

One LLM session holds everything at once: the full skill text, the golden examples, the area
context file, and the raw Jira ticket. Every decision is made with all of it visible.

That is the whole shape of Part I. The deterministic work — area resolution, file fetching,
enrichment triggers, parsing — happens in Java, where it is cheap and testable. The single
generation call does the judgment work, and it re-reasons from the raw ticket rather than from
a summary of one.

Auditability of intermediate reasoning is explicitly traded away for test case quality: the
flow keeps no per-step artifacts, only the finished plan and, on a retry, the human's feedback.

---

## 2. Flow architecture

**Background services** — not flow steps; cloned at startup, refreshed on demand:

```
[SkillWorkspaceService]   NEW   git clone/pull FACTORY_CYPRESS_FOLIO_AI_REPO
     └─ {user.dir}/skillsClone/skills/write-testrail-cases/
          SKILL.md · references/examples.md · references/context/*.md  (38 area files)

[TafSyncService]                git clone/pull stripes-testing
     └─ {user.dir}/cypressTafClone/
          cypress/support/fragments/** · permissions.js
```

**Pipeline:**

```
[Trigger: Jira story (manual trigger or Jira webhook)]
     │  ── payload: { issueKey } · optional inline issue for the zero-credentials path
     ▼
[cypress-testcase-gen-agent]                                            LLM
     │
     │ ── A · FETCH & ROUTE ──────────── non-LLM fetch + one small LLM routing call
     │
     │    jira.fetchIssue(issueKey) + linked issues
     │      ── RAW ticket straight into the prompt: verbatim summary, description,
     │           acceptance criteria, labels, dev team field
     │      ── inline trigger payload used instead when Jira is unconfigured
     │
     │    ROUTER CALL                                    LLM · ~5k in / ~200 out
     │      ── SKILL.md step 1 is AGENT JUDGMENT, not keyword matching:
     │           "the story itself is the source of truth"; story text outranks the
     │           Jira project prefix
     │      ── input: raw issue + linked issues + the 38-area table lifted from SKILL.md
     │      ── returns: areas[1–2] · one-line detection rationale
     │      ── AreaDetector validates the names against the 38 real context files;
     │           unknown / empty / call failure → keyword-score fallback
     │      ── output is 3 small FACTS, not a summary — the mega-call still receives
     │           the raw issue and re-reasons with everything visible
     │      ── `refs` (SKILL.md step 3 — what the case verifies) is NOT decided here.
     │           It moved to a per-case decision at Generate (below) once the router
     │           was found to be guessing blind at plan-shape before any case content
     │           existed — see "refs" under §6.3/§6.4 for the current design.
     │
     │ ── B · PREFETCH ───────────────── non-LLM · deterministic · produces no artifacts
     │
     │    ContextPrefetch.load(areas, issue)                      → context file(s)
     │      ── references/context/<area>.md read from the skill clone
     │      ── GitHub API → classpath, same fallback chain as today
     │      ── 5 thin-context triggers (< 10 KBRs · DRAFT marker · no "Exact UI Texts" ·
     │           < 3 toasts · > 5 known gaps) → GitHub + Jira enrichment
     │
     │    ExamplesPrefetch.load()                                 → examples.md
     │      ── golden TestRail cases with style annotations + anti-pattern section
     │
     │    FragmentPrefetch.scan(areas, summary)                   → fragment library
     │      ── score TAF fragments by filename (+3/kw) and content (+1/kw), top-2 per area
     │      ── matching gui permissions from permissions.js
     │
     │    CoveragePrefetch.load(areas)                            → existing coverage
     │      ── area-matched TestRail sections; dedup by case ID; cap 150
     │      ── placement-fallback section excluded by design (non-thematic)
     │      ── index = title + section + preconditions (truncated 200 chars)
     │
     │    cypress-coverage-triage over that index                  small LLM call
     │      ── nominates the cases whose titles could be a false match, cap 15
     │      ── Java renders those in full: preconditions + steps + expected results
     │      ── ids outside the index dropped; any failure → index only, never fails
     │
     │    testRail.listSectionPaths()                             → section list
     │
     │    every prefetch degrades to a placeholder string — none may throw
     │
     │ ── C · GENERATE ──────────────────────────────────────────── LLM · ONE call
     │
     │    system: SKILL.md verbatim + factory delta
     │            (no step-5 confirmation pause · no step-8 TestRail posting ·
     │             exact output envelope · exact case-block format)
     │    user:   examples · context file(s) · enrichment · RAW issue · linked issues ·
     │            fragments · coverage · section list · [hitl_feedback] · [previous plan]
     │      ── callForText → free-form markdown; no JSON schema, no structured output
     │      ── ~48–64k input tokens; everything the model needs is visible at once
     │
     │ ── D · VALIDATE ──────────────────────────────────────────── non-LLM
     │
     │    TestPlanParser.parse() on the agent's own output — the SAME parser sync uses
     │      ── zero cases · a case with no steps · a step with blank expected
     │           → one format-correction retry → then fail the step (engine escalation)
     │      ── the worker can never emit a plan that sync cannot parse
     │
     │ ── E · REVIEW LOOP ─────────────── LLM · up to maxReviewIterations (default 3, 0 = off)
     │
     │    cypress-testcase-reviewer scores the "# Test Cases" slice against the full
     │      R1–R19 checklist (grows over time; see `cypress-testcase-reviewer/system.md`
     │      for the current list — do not hardcode the count here again)
     │      ── FAIL → feedback + previous plan appended → regenerate (C) → revalidate (D)
     │      ── PASS or attempts exhausted → verdict written into "# Review"
     │
     │ ── F · ASSEMBLE ──────────────────────────────────────────── non-LLM
     │
     │    frontmatter written by the WORKER, never trusted from the LLM:
     │      issue_key         ← $trigger
     │      refs              ← deterministic union of every case's OWN refs (each case
     │                          may declare extra tickets it verifies; issue_key is
     │                          always first in every case's own set, by construction)
     │      team              ← Jira dev-team field (AUTHORITATIVE, canonicalised
     │                          via DevTeamCatalog) · else agent · else ""
     │      ecs_story        ← agent · non-ecs|ecs|mixed
     │      testrail_section  ← agent, VALIDATED via resolveSectionByPath() · else env default
     │      case_count · review_passed · review_iterations
     ▼
[test_plan.md]  — frontmatter + "# Analysis" + "# Test Cases" + "# Review"
     ▼
🛑 HITL Gate 1: Test Case Review
     │  ── "# Analysis" first: area detected, context file loaded, Key Business Rules
     │       touched, AC → scenario coverage matrix — judge whether the SCOPE is right
     │  ── "# Test Cases": exactly what will be posted to TestRail
     │  ── "# Review": automated reviewer verdict and any remaining issues
     │  ── frontmatter carries the sync fields — correct testrail_section here if wrong
     │       (path string with › separators, or numeric ID)
     │  ── AMEND edits inline · APPROVE continues · REJECT terminates permanently
     │  ── "Reject and Re-generate" (feedback required): original → REJECTED; a new
     │       execution starts at [cypress-testcase-gen-agent] with the current
     │       test_plan.md seeded + comments as hitl_feedback.md. Phase A re-runs
     │       (Jira is idempotent); Phase B revises the seeded plan rather than
     │       regenerating from scratch. No retry cap.
     ▼
[cypress-testrail-sync]                                              non-LLM
     │  ── TestPlanParser on the "# Test Cases" slice only
     │  ── push every case; all metadata from test_plan.md frontmatter
     │  ── automation_type=Manual · labels=[AI label] · team · ecs · refs (per case -
     │       re-derived from that case's own `- **Refs:**` line, not read off frontmatter)
     │  ── collect real C###### IDs per case
     ▼
[test_plan.md]  — TC-NNN tokens in body headings replaced with real C###### IDs
     ▼
[automation-select → automate → gate 2 → publish]  — Part III
```

## 3. Flow descriptor

`factory-flow-cypress/src/main/resources/flows/cypress-factory.yaml` (abridged - the gate
instructions are block scalars in the file):

```yaml
id: cypress-factory
version: 2.2.0
input_schema:
  required: [issueKey]
retry_policy:
  max_attempts: 3
  backoff_seconds: [30, 120, 300]
agent_chain:
  - step_id: testcase-gen            # Part I
    type: AGENT
    worker_id: cypress-testcase-gen-agent
    inputs: ["$trigger"]
    optional_inputs: [test_plan.md, hitl_feedback.md]
    outputs: [test_plan.md]
  - step_id: gate-1-review
    type: HITL_GATE
    gate: {gate_id: gate-1-test-cases, reviewed_artifacts: [test_plan.md, hitl_feedback.md],
           reference_prompts: [cypress-testcase-reviewer]}
  - step_id: testrail-sync
    type: AGENT
    worker_id: cypress-testrail-sync
    inputs: [test_plan.md]
    outputs: [test_plan.md, testrail_cases.json]
  - step_id: automation-select       # Part III
    type: AGENT
    worker_id: cypress-automation-select
    inputs: ["$trigger"]
    optional_inputs: [testrail_cases.json]
    outputs: [automation_cases.json]
  - step_id: automate
    type: AGENT
    worker_id: cypress-automate-cases
    inputs: [automation_cases.json]
    optional_inputs: [hitl_feedback.md]
    outputs: [automation_report.md, automation_results.json]
  - step_id: gate-2-review
    type: HITL_GATE
    gate: {gate_id: gate-2-autotests, reviewed_artifacts: [automation_report.md, hitl_feedback.md]}
  - step_id: publish
    type: AGENT
    worker_id: cypress-automation-publish
    inputs: [automation_results.json, automation_cases.json]
    outputs: [automation_publish.json]
```

**7 steps. 2 HITL gates.** Reject and Re-generate restarts at the AGENT step right before the
gate: `testcase-gen` for gate 1, `automate` for gate 2.

### The Gate 1 page

The gate is a plain server-rendered review page, but three details are load-bearing:

- **`reference_prompts`** surfaces the automated reviewer's checklist as a link to
  `/prompts/cypress-testcase-reviewer`, followed by the repo path of the file to edit —
  `PromptCatalog.sourceDirectory()` recovers the owning module from the resource URI. Without
  it nobody knows what the agent already checked, and approval rests on an invisible standard.
- **`review_instructions` is a block scalar**, rendered by `UiFormat.instructionBlocks` into
  real paragraphs and bullet lists. Descriptor text stays plain: no HTML is accepted from a
  flow descriptor and none is generated.
- **A decision legend** spells out what Approve / Amend / Reject / Reject-and-Re-generate each
  do, with the destructive Reject last.

Findings at the gate come from two sources merged into one list: the reviewer agent applying its prompt, and `TestPlanLint`. The reviewer prompt is linked from the gate footer and lists the code-checked rules by number, naming `TestPlanLint` as the place to change them — without that, a reviewer reading a `[R4]` finding would click through and find no R4 anywhere.

## 4. Asset availability and degradation

Every external asset resolves through the same three tiers, and the tier actually used is
recorded in `# Analysis` on every run:

```
1. LOCAL CLONE   {user.dir}/skillsClone · {user.dir}/cypressTafClone
                 git pull at startup; a stale clone is still used when the pull fails
2. GITHUB API    GitHubConnector — covers "git binary missing but token works"
3. CLASSPATH     bundled in the jar — last resort, always stale, always flagged
```

Degradation is **not uniform**. One asset is a hard dependency; the rest are soft:

| Asset | Feeds | All tiers fail |
|---|---|---|
| **`SKILL.md`** | the entire system prompt (~18k tokens of methodology) | **HARD — throw `AgentExecutionException`.** Without it there is no MQA process: we would generate from a nameless thin prompt and produce plausible-looking cases with no signal to the reviewer that anything was wrong. Engine retry (3× / 30·120·300s) covers a transient outage, then escalates to the review inbox. Silent quality collapse is worse than a failed step. |
| `references/context/<area>.md` | domain knowledge — KBRs, exact UI texts, permissions | **SOFT — SKILL.md sanctions it**: *"If a context file for the detected area does not exist… I'll generate cases based on the User Story alone — results may be less accurate."* Proceed; `# Analysis` states which area had no context file. |
| `references/examples.md` | few-shot style anchor | SOFT. Verification depth and formatting degrade; structure survives. |
| `references/writing-rules.md`, `step-patterns.md`, `house-style.md` (skill v2) | the rest of the system prompt - how a case is written | SOFT, but flagged loudly: provenance names any missing file and asks the model to say so at the top of `# Analysis`. |
| `references/examples/<area>.md` (skill v2) | per-area golden case, user message | SOFT and usually absent (10 areas have one). |
| TAF fragments | `automationNotes`, `automatable` flag | SOFT. Test case **content** is unaffected — only automation hints. `# Analysis` notes it so the Gate 1 reviewer knows to check the flags (the gate already says "override automatable flags"). |

**Bundle scope: `SKILL.md`, `examples.md`, the three rule files and `examples/*.md`** (~140 KB),
under `factory-flow-cypress/src/main/resources/references/`, paths preserved (the classpath tier
maps `references/x` to `references/x`, so `examples/orders.md` cannot collide with anything).

- Not the 38 context files (890 KB). Their absence has a documented graceful path, and checking
  in files we do not own creates a fork that drifts.
- `folio-eureka-ai-dev` is **public**, so tiers 1 and 2 both work with no credentials
  (`git clone` needs none; the GitHub API is unauthenticated at 60 req/hr, far above our usage).
  The bundle is therefore a genuine last resort, not the zero-credentials path — but it stays,
  because it covers: offline/air-gapped builds, unit tests that must not touch the network, a
  GitHub outage, and the repo later being made private, renamed, or moved.

**The bundle is always present in the jar.** `SKILL.md` and `examples.md` ship inside
`factory-flow-cypress` at `src/main/resources/references/` and are readable with no network, no
git binary, and no credentials. `examples.md` is already bundled today; P1 adds `SKILL.md`.

**Staleness is the real hazard, not availability.** The bundled `references/examples.md` is
currently **18,577 bytes** against **27,467** live — already ~33% behind, silently. Mitigations:

- `# Analysis` always states source and age, e.g.
  `Skill source: local clone @ 2026-08-27 (a1b2c3d) · context: marc-authority.md · examples: clone`
  and, on the last resort, `⚠️ examples.md from bundled classpath copy — may be outdated`.
- Startup WARN when the resolved `SKILL.md` differs in size by >10% from the bundled copy —
  cheap drift signal that the bundle needs refreshing.
- **NEW file** `scripts/update-cypress-testcase-skill-bundle.sh`, alongside the existing `reset-executions.sh` /
  `pg-backup.sh`. It copies the two files from a local skill clone into the module's resources
  and prints the size delta so drift is visible at the moment you refresh:

  ```bash
  #!/usr/bin/env bash
  # Refresh the bundled last-resort copies of SKILL.md and examples.md.
  # Usage: scripts/update-cypress-testcase-skill-bundle.sh [path-to-folio-eureka-ai-dev]
  set -euo pipefail
  SRC="${1:-$HOME/folio-eureka-ai-dev}/skills/write-testrail-cases"
  DST="factory-flow-cypress/src/main/resources/references"
  for f in SKILL.md references/examples.md; do
    out="$DST/$(basename "$f")"
    old=$( [ -f "$out" ] && wc -c < "$out" || echo 0 )
    cp "$SRC/$f" "$out"
    printf '%-12s %8s -> %8s bytes\n' "$(basename "$f")" "$old" "$(wc -c < "$out")"
  done
  ```

  Run it deliberately. Do **not** wire it into the Maven build — the bundle would silently become
  whatever the last person to build happened to have on disk.


## 5. Components

| Component | LLM? | Role |
|---|---|---|
| `SkillWorkspaceService` | No | git clone/pull of the FOLIO AI skills repo → local disk. Modelled on `TafSyncService`. Not a flow step. |
| `TafSyncService` | No | git clone/pull of `stripes-testing` → local disk. Not a flow step. |
| `AreaDetector` | No | **Validator + fallback** for the router call: resolves returned area names against the 38 real context files; keyword-scores story text + Jira prefix when the router fails or returns something unknown. |
| `ContextPrefetch` | No | Reads area context file(s) from the skill clone; runs the 5 thin-context triggers; enriches from GitHub/Jira when triggered. |
| `FragmentPrefetch` | No | Lists + keyword-scores TAF fragments for the detected area. An area maps to one or more directories or single files under `cypress/support/fragments` (`AREA_TO_DIRS`, default: the area name); they are scored as one pool, top 2 inlined, the rest listed. |
| `CoveragePrefetch` | No | First every case whose References cite the story or a related key (`getCasesByRefs`, skill step 4b - found wherever it is filed), then area-matched TestRail sections → existing cases; dedup by case id, cap 150. Renders a title + precondition index, and full bodies for triage-nominated candidates. |
| `ExamplesPrefetch` | No | Reads `references/examples.md` (system prompt) and, per detected area, `references/examples/<area>.md` (user message); clone, GitHub, then classpath. |
| `DevTeamCatalog` | No | The 27 FOLIO teams, parsed from `references/dev-teams.md`. Used by **both** workers, like `TestPlanParser`: the generator canonicalises the team it read off the ticket, sync turns that name into TestRail's `custom_dev_team` option id. Data lives in a resource so filling in a new team is an edit, not a release. |
| **`TestCaseGenAgentWorker`** | **Yes** | Fetches the Jira issue, runs the router call, assembles the mega-prompt, one call → markdown plan, then the reviewer loop. |
| ↳ router call | Yes | SKILL.md step 1 judgment: which area(s). ~5k in / ~200 out. `refs` (step 3) is decided per case by the generation call itself, not here — see §6.3. |
| ↳ reviewer loop | Yes | Existing pattern, same worker, `maxReviewIterations` (default 3, 0 = off). |
| ↳ `TestPlanValidator` | No | Parses the agent's own output before returning. One format-correction retry (an edit of the failed plan, not a regeneration), then fail the step. |
| ↳ `TestPlanLint` | No | Deterministic checks for the reviewer rules that are mechanically decidable — R3, R4, R5, R6, R9, R17, plus whether a case's preconditions survived parsing. Findings merge into the LLM reviewer's list and share its retry budget. |
| ↳ `MarcFixtureBuilder` | No | Merges a case's MARC breaker-text against the golden reference record for its kind. Called here (review loop) purely to surface structural errors as feedback; called again by `TestRailSyncAgentWorker` post-gate for the bytes actually uploaded. See §6.5. |
| Gate 1 | HITL | Human. |
| `TestRailSyncAgentWorker` | No | Parses the `# Test Cases` slice and pushes each case to TestRail with the frontmatter metadata; writes real case ids back into the plan. |

The worker uses no agent tool-calling: Spring AI 2.0.0 ships no filesystem tool support, and
prefetch covers what the generation call needs. The step boundary and the artifact contract are
independent of that choice — see Appendix A.

---

## 6. The artifact contract

### 6.1 `test_plan.md` — always produced

```markdown
---
issue_key: UIMARCAUTH-553
refs: "UIMARCAUTH-553, MODELINKS-201"
team: Spitfire
ecs_story: "non-ecs"
testrail_section: "MARC Authority › Browse"
case_count: 7
review_passed: true
review_iterations: 2
---

# Analysis

**Area detected:** MARC Authority — loaded `marc-authority.md`
*(router: story describes authority browse; UIMARCAUTH prefix agrees)*
**Context quality:** enriched (fewer than 10 KBRs) — fetched en_US.json, package.json

## Key Business Rules touched
- KBR: ...

## Coverage matrix
| AC | Scenario | Status |
|---|---|---|
| AC-1: ... | TC001 | NEW |
| AC-2: ... | Already covered by C12345 | EXISTING |

## Notes
...

# Test Cases

## TC001: Browse authority records by personal name
- **Test Group:** critical
- **User Journey:** No
- **Automatable:** Yes
- **ECS:** No
- **Refs:** MODELINKS-201                  ← optional; see §6.3/§6.4 "refs"

**Preconditions**
- ...

**Steps**
1. **Action:** ...
   **Expected:** ...

**Automation notes:** ...

# Review

**Result:** PASSED after 2 iteration(s)
...
```

On a retry the seeded `test_plan.md` is itself the plan being revised. A step may declare the
same artifact as both input and output; the store versions it rather than overwriting.

**Where the frontmatter values come from.** The worker owns all eight fields; the agent
contributes to only three of them, and never has the last word:

| Field | Source |
|---|---|
| `issue_key` | The trigger payload. |
| `refs` | Deduplicated union of every case's own `refs` (see §6.3 "refs" below) — a plan-level rollup, not a separately-decided value. |
| `team` | **Jira's Dev Team field is authoritative** — `customfield_10057`, which `JiraRestConnector` renames to `devTeam`. Canonicalised through `DevTeamCatalog`. The agent's reading is used only when the ticket has no team, because it has no other source and would otherwise infer one from the subject matter. |
| `ecs_story` | The agent, from labels and story text. `non-ecs`, `ecs`, or `mixed`. |
| `testrail_section` | The agent's suggestion, but only if TestRail resolves it; otherwise the configured default. |
| `case_count`, `review_passed`, `review_iterations` | The worker, from its own parse and review loop. |

The three fields the agent emits inside `# Analysis` (delta 4) are **stripped from the prose**
once promoted, by `stripPromotedFields`. Models reliably emit them but tend to wrap them in
`---`, which renders as a stray second frontmatter duplicating the real one. The strip is scoped
above `# Test Cases` so a step that legitimately contains `team: X` survives.

### 6.2 `hitl_feedback.md` — retry runs only

Written by `HitlRetryService` from the reviewer's rejection comment. Cannot fold into
`test_plan.md`: it is created outside the worker, and the worker must distinguish "the plan I am
revising" from "what the human said about it".

### 6.3 Sync parseability — four guarantees

**G1 — the parser is scoped to `# Test Cases`.**
Today `parseCasesFromBody()` splits the whole body on `^(?=## )`. With `# Analysis` in the same
file, a heading like `## Notes: coverage` matches `CASE_HEADING` (`^## (.+?):\s+(.+)$`) and
produces a garbage case. Fix: slice the body from `# Test Cases` to the next `^# `, parse `##`
inside that slice only. Nothing outside the section can be mistaken for a case.

**G2 — one parser, two callers.**
Extract into `org.folio.factory.cypressfactory.plan.TestPlanParser`. The generator uses it as a
validator; sync uses it as the parser. Same code, no drift — if it parses at generation time it
parses at sync time by construction.

**G3 — the worker never emits an unparseable plan.**
After generation the worker parses its own output. Zero cases, or any case with zero steps, or
any step with a blank `expected` → one format-correction retry → then throw
`AgentExecutionException` and let engine retry/escalation handle it.

**G4 — frontmatter is worker-guaranteed, not LLM-trusted.**
The worker writes the frontmatter itself; the agent only supplies three of the values, in a
parseable form inside `# Analysis`.

| Field | Source | Fallback |
|---|---|---|
| `issue_key` | `$trigger` payload | — (required) |
| `refs` | Union of every case's own per-case refs (see below) | `issue_key` alone, if no case declares any |
| `team` | **Jira dev-team field first**, else the agent's reading; canonicalised via `DevTeamCatalog` | `""`, and sync logs a warning naming the team rather than dropping it silently |
| `ecs_story` | agent | `non-ecs` |
| `testrail_section` | agent, **validated via `testRail.resolveSectionByPath()` before writing** | config default `FACTORY_CYPRESS_TESTRAIL_SECTION_ID`; note added to `# Analysis` |
| `case_count` | validator | — |
| `review_passed`, `review_iterations` | reviewer loop | — |

**`refs` — the ticket key is non-negotiable, and it's a *per-case* decision.** `refs` is
TestRail's **References** field; a case that loses its originating ticket is orphaned. This was
originally a router-time, plan-wide guess (the router proposed extra keys before any case
content existed), which produced plans where the router's prediction didn't match what the
cases it hadn't written yet actually turned out to verify. It is now decided by the *generation*
call itself, per case — the same call that has the linked-issue content in front of it and knows
which case regression-covers which linked Bug/Tech-Debt ticket, if any. A case adds a
`- **Refs:** JIRA-1234[, JIRA-5678]` attribute line (see §6.1) only when it specifically verifies
a `## Linked issues` ticket's behaviour — never the parent epic or a sibling story Jira happens
to link. Most cases add nothing.

```java
// TestCaseGenAgentWorker, shared with TestRailSyncAgentWorker (same package)
static Refs buildRefs(String issueKey, List<String> caseRefs, Set<String> excludedProjects) {
    List<String> refs = new ArrayList<>();
    refs.add(issueKey);                                   // always first, per case
    List<String> dropped = new ArrayList<>();
    for (String extra : caseRefs) {
        String k = extra.strip().toUpperCase(Locale.ROOT);
        if (!JIRA_KEY.matcher(k).matches() || refs.contains(k) || dropped.contains(k)) continue;
        if (excludedProjects.contains(projectKey(k))) dropped.add(k); else refs.add(k);
    }
    return new Refs(refs, dropped, excludedProjects.contains(projectKey(issueKey)));
}
// JIRA_KEY = Pattern.compile("[A-Z][A-Z0-9]*-\\d+")
```

The regex filter drops anything that is not a well-formed Jira key, so a hallucinated or
malformed case value is discarded rather than written into TestRail. `buildRefs` is called
twice: once per case at **Assemble** (phase F) purely to compute the plan-level `refs` frontmatter
as a deduplicated union (`unionRefs`, for a human skimming the gate), and again per case inside
`TestRailSyncAgentWorker`'s create loop — the *actual* TestRail value each case gets, derived
fresh from that case's own `refs()` at sync time so a Gate 1 amendment to a case's `- **Refs:**`
line is honoured. `issue_key` is present in every case's `refs` by construction at both layers,
so a reviewer amendment can only add or remove *extra* keys, never orphan the case from its
originating ticket.

### 6.4 What the sync worker writes into TestRail

Every value comes from `test_plan.md` — the frontmatter for case-wide fields, the parsed case
block for per-case fields.

| TestRail field | Value source | Notes |
|---|---|---|
| `section_id` | `testrail_section` from the frontmatter | Path string (separators `›` `|` `>` `→`, resolved via the API) or a numeric id. Falls back to `FACTORY_CYPRESS_TESTRAIL_SECTION_ID` when absent or unresolvable. |
| `custom_test_group` | `Test Group` on the case block | 1 = smoke, 2 = criticalPath, 3 = extendedPath |
| `custom_dev_team` | `team` from the frontmatter, mapped through `DevTeamCatalog` | Left unset when the team is unknown or has no TestRail id; both cases log a warning naming the team. |
| `custom_ecs_enabled` | per-case `- **ECS:** Yes/No` attribute | Set directly from the per-case flag; `ecs_story` is a generation-calibration hint only and does not override per-case decisions. |
| `custom_automation_type` | `2` (Manual) for every case | The Cypress spec is the automation artifact; the TestRail case stays a manual record. |
| `labels` | `[id of testrailLabel]` | Resolved at runtime via `getOrCreateLabel`; title from `FACTORY_CYPRESS_TESTRAIL_LABEL`, default `cypressFlow-delete`. |
| `refs` | Re-derived per case via `buildRefs(issueKey, case.refs(), excludedProjects)` | **Not** read from the frontmatter rollup — each case gets its own value from its own `- **Refs:**` line, so a Gate 1 amendment to one case's refs doesn't require touching the frontmatter. `issue_key` is re-asserted for every case even if its `- **Refs:**` line was amended to omit it. |
| `custom_preconds` | The case's Preconditions block | Rendered as a TestRail HTML list. |

### Precondition format

Preconditions are a flat list. A nested detail — a capability set or permission list — is an
indented `  - ` line under its parent in the markdown, which `TestPlanParser` re-groups onto that
parent element as a `\n-` sub-bullet. `toHtmlListItem()` renders each `\n` as `<br>` inside the
TestRail HTML `<li>`.

### Real ID writeback

The worker builds a `TCNNN → C######` mapping as it creates cases, then rewrites the
`test_plan.md` body: each heading changes from `## TC001: Create order` to
`## C627249: Create order`. The frontmatter passes through as-is. The updated `test_plan.md` is
the sole output.

The body is the single source of truth for case data. Downstream workers parse cases from body
headings using the same `## ID: Title` pattern — after this step, `tc.id()` returns `C627249`.

**Partial failure caveat:** if `addCaseWithMetadata` throws for one case, that `TCNNN` token
stays unreplaced in the body. Automation is not affected: it takes case ids only from
`testrail_cases.json`, which lists only the cases TestRail accepted.

### Section hierarchy for `describeBlocks`

After creating each case, fetch the assigned section hierarchy the same way the MCP server does:
walk `parent_id` up to `depth == 0` via `GET /api/v2/get_section/{id}`. Store as
`describeBlocks` in the mapping for use in the finalizer PR description (informational only —
the specs already have correct `describe()` nesting from the generation step).

The Java connector will need a `getSection(sectionId)` method added.

### 6.5 Test-data fixture attachments (MARC / text / CSV)

A case that is genuinely unverifiable without a concrete file (import a MARC record, bulk-edit
from a CSV) may declare `- **Attachment:** kind=text|csv|marc-bib|marc-authority|marc-holdings`
plus a fenced `**Attachment content**` block, per §6.1's case-block format. Most cases carry no
attachment at all — never generate one "just in case" (reviewer R19).

**`text`/`csv`** is the file's exact bytes, written verbatim.

**MARC kinds are breaker-text, not a full record, and not the model's job to get byte-perfect.**
`org.folio.factory.cypressfactory.plan.MarcFixtureBuilder` merges the model's sparse breaker-text
against a real, checked-in golden reference record per kind
(`factory-flow-cypress/src/main/resources/references/marc-examples/*.mrc`, one working record per
kind supplied by MQA) — any field the model writes (including the pseudo-tag `LDR` for the
leader) replaces the golden value for that exact field; anything it doesn't mention inherits from
golden unchanged. `MarcStreamWriter` computes the leader/directory byte arithmetic from actual
content, so the model is never asked to hand-count offsets. The generation prompt's
`{{marc_examples}}` placeholder renders all three golden records in full via
`MarcFixtureBuilder.renderGoldenExamples()`, so the model always has a real worked example to
overlay onto, never a hand-transcribed one that could drift from what the builder actually merges
against.

**The key content field (245 for bib; one of 100/110/111/130 for authority; 852 for holdings) is
the one field the model must always write itself**, even when the scenario has nothing to say
about its content — the golden record's own value is a real, fixed title/heading, and leaving it
to inherit would collide across cases. The model writes its own case id there as a placeholder
(e.g. `TC004_MarcBibInstance`, since the real TestRail id doesn't exist yet at generation time);
`TestRailSyncAgentWorker.uploadAttachment()` substitutes the model's case id for the real one in
the fixture bytes at sync time — the same substitution `replaceIds` applies to the plan body,
just applied to the MARC content before it's built and uploaded, since attachment upload happens
inside the per-case creation loop, before that single body-wide pass. Uploaded filenames follow
`<TestRail case id><RecordType>.mrc` (e.g. `C1538594MarcBibInstance.mrc`), not the case's raw
attachment-kind spec.

**A case needing more than one record of the same kind** (e.g. two bib records to link) writes
them one after another in the same fenced block, separated by a blank line —
`MarcFixtureBuilder.splitRecords()`/`buildAll()` build one record per block, and the sync worker
suffixes both the filename and the key-field value `_1`, `_2`, ... in the order written; the
model never numbers them itself.

Deterministic and LLM-free by design (`build()`/`buildAll()` take no LLM call): used twice —
once during the review loop purely to surface structural errors as regeneration feedback (bytes
discarded), and again by `TestRailSyncAgentWorker` post-gate to build the bytes actually
uploaded, since a human can amend the attachment attribute or content at Gate 1.

---

## 7. Component reference

### Skill workspace

**New:** `factory-flow-cypress/.../worker/SkillWorkspaceService.java`

`SkillWorkspaceService` mirrors `TafSyncService`: `syncInBackground()`, `isReady()`, `sync()`,
`cloneRepo()`, `pull()`, `gitAvailable()`, `run(String...)`, all with `-c core.longpaths=true`
for Windows path limits.

- Clones `properties.folioAiRepo()` into `{user.dir}/skillsClone`
- `Path skillRoot()` → `<clone>/skills/write-testrail-cases`
- `Optional<String> read(String relativePath)` — `SKILL.md`, `references/examples.md`,
  `references/context/<area>.md`
- `boolean isReady()`

**Fallback chain** — see Part I §4 for the full contract. `read()` resolves local clone → GitHub API
(via `GitHubConnector`) → classpath `references/`, and returns which tier answered so
`# Analysis` can report it:

```java
public record Asset(String content, Source source, String detail) {}
public enum Source { CLONE, GITHUB, CLASSPATH }
public Optional<Asset> read(String relativePath);
public String requireSkill();   // throws AgentExecutionException — SKILL.md is a hard dependency
```

**Alongside the service:**
- `SKILL.md` and `examples.md` are bundled in
  `factory-flow-cypress/src/main/resources/references/` as the always-present last tier
  (Part I §4).
- `scripts/update-cypress-testcase-skill-bundle.sh` refreshes that bundle (content in Part I §4).
- Startup WARN if the resolved `SKILL.md` differs by >10% in size from the bundled copy.

A bean in `CypressFlowConfiguration` calls `syncInBackground()` on `ApplicationReadyEvent`,
alongside the TAF sync.

### Prefetch services

Plain Spring beans, not `AgentWorker`s: the generation worker calls them directly, and they
produce no artifacts.

| Service | Entry point |
|---|---|
| `AreaDetector` | `resolve(List<String> routerAreas, JsonNode issue) → List<String>`<br>`areaTable() → String` (the 38-row table for the router prompt) |
| `ContextPrefetch` | `load(List<String> areas, JiraIssue issue, List<JiraIssue> linkedIssues) → ContextBundle(text, sourceNote, missingAreas, enriched, qualityNote, linkNotes)` |
| `FragmentPrefetch` | `scan(List<String> areas, String summary) → String` |
| `CoveragePrefetch` | `load(List<String> areas) → Coverage(index, cases, sections)`<br>`Coverage.select(List<Long>, int cap)`, `Coverage.renderCandidates(List<Long>)` |
| `ExamplesPrefetch` | `load() → String` |
| `InlineImagePrefetch` | `load(JiraIssue issue, boolean visionEnabled) → InlineImages(media, placeholders, notes)` |

Notes:
- `AreaDetector` holds `AREA_KEYWORDS`, `PROJECT_KEY_AREAS` and `detectAreas()`. It does not
  choose the area — the router call does. Its two jobs:
  - `resolve(routerAreas, issue)` — map each router-returned name to one of the 38 real context
    files (case-insensitive, hyphen/space tolerant). Any name that does not resolve is dropped
    and logged at WARN. If nothing resolves, or the router call failed, fall back to keyword
    scoring on raw issue `summary` + `description` + `labels` + project key.
  - `areaTable()` — the 38-row Area/context-file/keywords table, rendered for the router prompt.
- Cap at 2 areas (SKILL.md: "A story can span two areas — read both context files"). The router
  is instructed to return at most 2; `resolve()` truncates if it returns more.
- `ContextPrefetch` owns `isThinContext`, `isDraftContext`, `countKeyBusinessRules`,
  `countToastsInUiTexts`, `countKnownGaps`, `enrichGitHub`, `fetchPermissions`,
  `fetchTranslations` and `AREA_GITHUB_REPOS`. It reads the context file through
  `SkillWorkspaceService.read("references/context/" + area + ".md")`.
- **Linked-page resolution** is a chain, most authoritative source first, because scraping
  rendered HTML is the worst representation of most documentation: `readConfluencePage`
  (REST, works anonymously on a public space) → `readStorybookStory` (`index.json` → repo
  readme) → `readMarkdownVariant` (`<url>.md`, content-type guarded, since a SPA answers 200
  with its shell for any path) → `scrapeHtmlPage`. A plausibility floor
  (`MIN_PLAUSIBLE_PAGE_CHARS`) stops a JS shell's leftover chrome being accepted as content.
  Three Confluence URL forms resolve to the same REST call: `/wiki/spaces/<KEY>/pages/<id>`,
  the legacy `/wiki/pages/viewpage.action?pageId=<id>`, and the tiny `/wiki/x/<hash>` — whose
  hash *is* the page id, base64url of its little-endian bytes, so it needs no extra request.
  (Following the tiny link's redirect does not work: it lands on `tinyurl.action`, which answers
  202 with an AWS WAF bot challenge.)
- **Budgets**: `MAX_LINK_PAGES` (4) caps pages *accepted into the prompt*, `MAX_LINK_ATTEMPTS`
  (8) caps distinct URLs tried; a failed read costs an attempt, not a page slot. Candidates are
  ordered story-links-first, and known-resolver URLs first within each group, so a ticket citing
  junk ahead of its spec cannot starve the spec. Every link that is truncated, unreadable or cut
  off by a budget is reported at the gate through `linkNotes` — budget skips share one grouped
  note.
- `InlineImagePrefetch` sends the model images embedded **inline in the story's own
  description** - and only those. An author who writes "Something like this:" above a screenshot has
  put requirements in it (UIU-3562 specifies its accordion that way); an attachment that is merely
  attached carries no such signal, since that list accretes error shots, HARs and debugging files.
  Linked issues are skipped too - their descriptions are already clipped to a char budget for
  attention reasons. The scoping needs no rule of its own: an attachment nothing references never
  matches. Images go to the **generation call only** (`media(promptId, variables)` keyed on
  `GEN_PROMPT_ID`), since the router picks an area and the reviewer sees only the rendered cases.
- **Image identity comes from Jira's rendered HTML, which is why this works on v2 and v3 alike.**
  Neither description body identifies an image usefully: v2 wiki markup has
  `!image-20260430-204850.png|width=844!` (a filename, which at least matches an attachment) and a
  v3 ADF body has `![](blob:...id=00d03e57-...)` - a Media Services uuid matching nothing, since
  attachment entries carry no media id. The rendered body is
  `<img src=".../attachment/content/122774" alt="image-20260430-204850.png">` in both, carrying the
  real attachment id, the filename and the document position. So `fetchIssue` opts into
  `JiraConnector.getIssue(key, withRenderedBody = true)` and the rendered images are paired with the
  description's image tokens **by document order** - both lists come from the same document, so
  order is exact rather than a guess. Filename matching remains as the fallback for a run with no
  rendered body at all (a manual trigger that passes the story inline), and a v3 body arriving
  without one is reported at the gate rather than looking like a story with no pictures.
- **Downloading an attachment takes two hops, and that is not optional.** Jira answers the
  attachment content endpoint with **303** and a `Location` pointing at a pre-signed
  `api.media.atlassian.com` URL; only that second URL carries the bytes. Spring `RestClient` does
  not follow redirects, so the first implementation silently got an empty body - UIU-3562 reached
  the gate with both mockups marked "download failed" (execution 7c0caa24) while every other part
  of the chain had worked. The second hop deliberately uses a **credential-free** client cloned
  before the Authorization default is added: the signed URL authorizes itself, and forwarding Basic
  auth to another host would leak it for nothing. Turning redirect-following on for the shared
  client would have done exactly that, and to every other Jira call.
- Every image that does not reach the model leaves a placeholder in the description text **and** a
  gate note. `FACTORY_CYPRESS_SEND_IMAGES=false` turns the whole thing off, which matters because a
  non-vision model rejects a call carrying media rather than ignoring it.
- **Skill v2 (folio-eureka-ai-dev#50, merged 2026-11-10).** SKILL.md became a 14 KB workflow;
  the rules moved to `references/`. What the flow does with each part:
  - `writing-rules.md`, `step-patterns.md`, `house-style.md` - pasted into the generation system
    prompt after SKILL.md (`SkillWorkspaceService.RULE_FILES`, `{{skill_references}}`). Cached
    with the rest of the system prefix, ~10k tokens.
  - `examples/<area>.md` - per-area golden case, user message (`{{area_examples}}`), so the shared
    system prefix stays identical across areas.
  - `area-map.md` - not sent; the router and `AreaDetector` own detection. Its table is the source
    for `KNOWN_AREAS` / prefixes / keywords (8 new areas added: inn-reach, open-access,
    serials-management, claiming, export-manager, linked-data, marc-holdings, inventory-browse).
  - `testrail-api.md` - not sent; posting is the factory's (delta 3).
  - Step 4 (sample the target section's 10 newest cases) - **not implemented**; delta 1 tells the
    model to use the `house-style.md` / `writing-rules.md` defaults instead. Idea in `next_steps.txt`.
  - Step 4b (existing coverage by `refs`) - `CoveragePrefetch.load(areas, refKeys)`; delta 1 maps
    the skill's duplicate / update / unrelated classification onto the coverage block.
  - Capability set names (delta 9) follow the skill's order (context -> existing cases ->
    `package.json`) and its marker `(capability set name not confirmed — verify)`, with one
    correction: from `package.json` the flow uses `permissionName`, not `displayName` - the display
    name is the legacy Okapi label, which the skill itself forbids in place of a capability set.
  - Enrichment reads `translations/<module>/en.json`, falling back to `en_US.json`.
  - Delta 11 (User Journey flag) now defers to the area norm (context file, else
    `house-style.md`). Its old wording - "a case covering two numbered scenarios is a journey" -
    overrode skill v2's "multi-step != journey" once v2 started merging scenarios, and flagged
    every eureka case `Yes` (UIROLES-202, d8bc4d52) in an area that marks every case `false`.
- `KNOWN_AREAS` must match the 38 context filenames exactly (`agreements`, `bulk-edit`,
  `check-in`, …) - the router can only choose from it, so a context file missing from the list is
  unroutable (this hid `eureka` from the router: UIROLES-202 was grounded on `users.md`).
  `SkillWorkspaceService.warnOnAreaDrift()` logs a startup WARN for any context file in the clone
  that is not in `KNOWN_AREAS` (`unroutable:<stem>`) and any known area with no file
  (`missing-file:<stem>`). When MQA adds a context file, also add its Jira prefixes and keywords to
  `PROJECT_KEY_AREAS` / `AREA_KEYWORDS`.
- A routable area also needs page objects, or `FragmentPrefetch` finds none and the plan's
  automation notes say a new page object would be needed. Areas whose TAF directory is not named
  after the area go in `FragmentPrefetch.AREA_TO_DIRS`. `eureka` has no `fragments/eureka` (its
  specs live under `e2e/eureka`), so it maps to `settings/authorization-roles`,
  `settings/authorization-policies`, `users/userEdit.js` and `users/usersCard.js` - the page objects
  those specs import. Measured on UIROLES-202 (0575e47f vs f2634b2c): 0 -> 2 fragments inlined,
  automation notes cite 12 real `authorizationRoles.*` helpers, labels match the page object; cost
  ~+33k uncached prompt tokens per attempt (`userEdit.js` is 78 KB and only loosely relevant to
  roles stories - drop it from the mapping if cost matters more than user-pane coverage).

### The generation worker

`factory-flow-cypress/.../worker/TestCaseGenAgentWorker.java`

Constructor:
```java
TestCaseGenAgentWorker(ChatClient chatClient,
                       JiraConnector jira,
                       TestRailConnector testRail,
                       SkillWorkspaceService skills,
                       AreaDetector areaDetector,
                       ContextPrefetch contextPrefetch,
                       FragmentPrefetch fragmentPrefetch,
                       CoveragePrefetch coveragePrefetch,
                       ExamplesPrefetch examplesPrefetch,
                       FrontmatterCodec frontmatterCodec,
                       TestPlanParser parser,
                       DevTeamCatalog devTeams,
                       PromptDump promptDump,
                       CypressFlowProperties properties)
```

`execute(AgentContext)`:

1. **Issue** — `issueKey` from `$trigger`. If the trigger carries an inline `issue` object, use
   it; otherwise `jira.fetchIssue(key)` plus linked issues. Zero-credential path must still work.
2. **Route** — `callForText(ROUTER_PROMPT_ID, {issue, linked_issues, area_table})`.
   Returns `areas` and a one-line rationale (area detection only — `refs` is no longer a router
   decision, see below). `areaDetector.resolve(...)` validates; any failure (call error,
   unparseable, nothing resolves) falls back to keyword scoring — **the router must never break
   the run**.
3. **Prefetch** — context (+ enrichment), examples, fragments, coverage, TestRail section list.
   Every prefetch degrades to a placeholder string on failure; none may throw.
4. **Generate** — `callForText(GEN_PROMPT_ID, vars)`. Free-form markdown; not
   `callForEntity`, and no structured-output schema.
5. **Validate** — `parser.parse(planMarkdown)`. On failure, one retry with a format-correction
   message appended; on second failure throw `AgentExecutionException`. **The retry is an edit,
   not a re-roll**: it carries the unparseable plan back in `previous_plan_block` and is told a
   format fix may not drop cases or re-derive `# Analysis`. Without that it regenerated the whole
   document from the original prompt — observed losing a case *and* the `## Scenarios` section
   while rewriting the coverage matrix to agree with the shorter list (so no matrix-vs-cases
   cross-check could have caught it), and once returning a 1-character response that killed a
   12-case plan. `checkForRetryRegression` compares case and scenario counts across the retry off
   the raw text and reports a shrink at the gate rather than failing a structurally valid plan.
6. **Review loop** — reviewer receives the `# Test Cases` slice. On FAIL, append feedback + the
   previous plan to `vars` and regenerate (re-validating each time). Two deterministic checks run
   beside it and merge into the **same** findings list and retry budget: `MarcFixtureBuilder` for
   attachment bytes, and `TestPlanLint` for the checklist rules that are mechanically decidable
   — so a lint finding overrides a reviewer PASS.
7. **Assemble** — worker writes frontmatter per Part I §6.3 G4, validates `testrail_section` via
   `testRail.resolveSectionByPath()`, appends the `# Review` section, returns
   `AgentResult` with `test_plan.md` + accumulated token metrics (`drainUsage()` / `accumulate`
   pattern, unchanged).

Retry inputs: `test_plan.md` (optional) → "revise this, do not regenerate from scratch";
`hitl_feedback.md` (optional) → a priority corrections block. Built by `buildPreviousPlanBlock`
and `buildHitlFeedbackBlock`.

`maxTokens()` is `16384`. `chatOptions()` also sets a **15-minute per-request call timeout** (`FACTORY_CYPRESS_LLM_TIMEOUT`, default 15m) via `AnthropicChatOptions.Builder.timeout(Duration)`. This overrides the shared `FACTORY_LLM_TIMEOUT` default — Spring AI builds a separate client keyed on client-level settings including timeout, so the per-flow value wins. The shared default stays at 300s for other flows.

**Prompts — router:** `factory-flow-cypress/src/main/resources/prompts/cypress-area-router/`

`system.md` — SKILL.md's **Area Detection** rules verbatim (the "story is the source of truth" /
"trust story over prefix" paragraphs) — area detection only, one judgment call, not two. SKILL.md
step 3 (`refs`) used to live here too; it was moved to the generation prompt (delta 6, see below)
because the router has to guess at plan shape before any case exists, while the generation call
already knows exactly which case verifies which linked ticket, if any. Plus:

> Return exactly this block and nothing else:
> ```
> AREAS: <context-file-stem>[, <second-stem>]
> WHY: <one line>
> ```
> Use at most 2 areas, and only stems from the table. If the story spans two areas, list both.

`user.md` placeholders: `{{issue}}`, `{{linked_issues}}`, `{{area_table}}`.

Parsed with three line-prefix regexes. Any parse failure → `AreaDetector` keyword fallback,
logged at WARN. Deliberately **not** structured output — a 3-line block is cheaper and more
robust than a JSON schema here.

**Prompts — generation:** `factory-flow-cypress/src/main/resources/prompts/cypress-testcase-gen/`

`system.md` = `{{skill}}` (SKILL.md verbatim from the clone) + a **factory delta** block +
`{{examples}}` + the valid team list `{{dev_teams}}`. Everything in the system prompt is
constant across calls and across runs, which is what makes it cacheable — see the prompt
caching subsection below.

**The deltas describe workflow parts by behaviour, never by step number.** SKILL.md is
maintained by MQA and gets renumbered and reorganised; a delta that says "skip step 8" points at
the wrong thing the moment a step is inserted, and nothing errors — the run just quietly does
the wrong thing. Behaviour descriptions are specific enough to match and loose enough to survive
edits. The block also carries an explicit fallback: *if a delta no longer maps onto the workflow
at all, follow the stated intent and note the mismatch at the top of `# Analysis`* — which turns
silent drift into a visible one-line report at Gate 1.

> - **Everything before scenario analysis is already done** — area detection, reading the
>   matching context file, the thin-context assessment and enrichment, the golden examples, and
>   the ticket. Begin where the workflow maps the story onto the context file's Key Business
>   Rules. If the supplied context file clearly does not match the story, say so in the first
>   line of `# Analysis` rather than generating against the wrong domain knowledge.
> - **Never pause for confirmation.** Wherever the workflow says to present something and wait —
>   the scenario list above all — write it into `# Analysis` and carry straight on. A human
>   reviews the whole document at one gate afterwards.
> - **Never touch TestRail.** Ignore anything about asking for a Section ID, showing a posting
>   preview, or calling the API. Placement is expressed only as `testrail_section:` in `# Analysis`.
> - Also state `team:`, `ecs_story:` and `testrail_section:` on their own lines in `# Analysis`.
> - Additional per-case fields not in the skill: `- **Automatable:** Yes/No`, an optional
>   `- **Refs:** JIRA-1234[, ...]` (delta 6 — see "refs" under §6.3 above; most cases omit it),
>   and an optional `**Automation notes:**` line referencing real helpers from the fragment
>   library.
> - Output envelope: exactly three top-level sections, `# Analysis`, `# Test Cases`, `# Review`
>   (leave `# Review` empty — the factory fills it). No YAML frontmatter — the factory writes it.
> - Inside `# Analysis` and `# Review`, never use a `##` heading containing a colon.
> - Case block format is **exact** — reproduced verbatim in the prompt, matching `TestPlanParser`.

`user.md` placeholders: `{{context_files}}`, `{{enrichment}}`, `{{issue}}`,
`{{linked_issues}}`, `{{fragments}}`, `{{coverage}}`, `{{sections}}`, `{{hitl_feedback_block}}`,
`{{previous_plan_block}}`, `{{review_feedback}}`.

**`{{examples}}` deliberately lives in `system.md`, not here.** It is static, so putting it in
the cached prefix costs nothing; moving it back into the user message would silently disable
caching for ~7k tokens per call.

**Reviewer prompt:** `cypress-testcase-reviewer/`. `user.md` receives `{{issue}}` and the
`# Test Cases` slice; the checklist rules live in `system.md`.

**Detecting delta drift — three layers.** Behaviour-worded deltas survive renumbering, but not
*removal*: if MQA deletes the confirmation pause outright, delta 2 overrides nothing and nobody
finds out. `SkillWorkspaceService.DELTA_ANCHORS` maps each delta to one phrase that is unique in
SKILL.md, and `unanchoredDeltas(skillText)` names the deltas whose phrase has gone:

| Anchor phrase | Guards |
|---|---|
| `read the matching context file` | delta 1 — gathering already performed |
| `Wait for confirmation` | delta 2 — never pause for confirmation |
| `Ask for the TestRail Section ID` | delta 3 — never touch TestRail |

- **Startup** — `warnOnDeltaDrift()` logs a WARN naming the affected deltas after each sync.
- **Gate 1** — the worker appends the same finding to the Provenance block, so the reviewer sees
  `⚠️ SKILL.md has changed: delta 3 … no longer match` in `# Analysis` on the run itself.
- **Bundle refresh** — `scripts/update-cypress-testcase-skill-bundle.sh` re-checks the anchors
  against the newly copied file and prints ok/MISSING per delta. This is the moment a human is
  deliberately looking at the skill, so it is the cheapest place to catch it.

Keep the script's anchor list and `DELTA_ANCHORS` in step; `SkillWorkspaceServiceTest` asserts
the bundled SKILL.md still anchors all three, so a refresh that breaks one fails the build.

**Optimization — verified and implemented.** SKILL.md (~18k tokens) + `examples.md` (~7k) are
stable across every run and every review iteration, so both now live in `system.md` and the
generation call requests Anthropic prompt caching over it.

Spring AI 2.0.0 supports this properly — no hand-placed breakpoint needed. `AnthropicCacheOptions`
with `AnthropicCacheStrategy.SYSTEM_ONLY` marks the system prompt; the library decides placement:

```java
@Override
protected ChatOptions.Builder<?> chatOptions() {          // hook on AbstractLlmAgentWorker
    return AnthropicChatOptions.builder()
            .maxTokens(maxTokens())
            .cacheOptions(AnthropicCacheOptions.builder()
                    .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                    .messageTypeTtl(MessageType.SYSTEM, AnthropicCacheTtl.FIVE_MINUTES)
                    .build());
}
```

Design constraints that shaped it:

- **`maxTokens()` was kept, not replaced.** The default `chatOptions()` builds from it, so any
  existing override keeps working. Replacing it would have compiled fine and silently stopped
  being called.
- **The Anthropic dependency lives in this module's pom**, never in `factory-agents` — provider
  swapping via `FACTORY_LLM_PROVIDER` is a live capability. On OpenAI the options degrade to a
  warning and caching just does not happen.
- **Five-minute TTL, deliberately.** The TTL is a sliding window refreshed on each read, so it
  survives a whole run however long the run takes. One-hour TTL costs meaningfully more per
  write and only pays off for *cross-run* hits; revisit if `cacheReadTokens` shows those are
  being missed often.
- **The cached prefix must stay byte-identical.** Review feedback and the previous plan stay in
  the user message. `systemMessageIsIdenticalAcrossReviewIterationsSoTheCachePrefixHolds` pins
  this — moving any per-attempt value into `system.md` fails that test rather than silently
  disabling caching.

Verification is empirical only: `StubChatModel` ignores options, so no test can prove caching
engages against the real API. `AgentResult` metrics now carry `cacheWriteTokens` /
`cacheReadTokens` (read through the portable `Usage` interface) and reach both the `/audit`
`STEP_COMPLETED` detail and `factory.llm.tokens{type=cache_write|cache_read}`. A non-zero
`cacheReadTokens` on a run's later generation calls is the signal it is working — see Part I §11 step 12.

**Scope: this worker only.** `chatOptions()` is a per-worker override and cache entries key on
the prompt prefix, so entries never cross workers. A future test-automation or debugging worker
gets no benefit from this one and must opt in itself — worth it only where a *large, byte-stable*
prefix exists. A debugging agent whose input is dominated by a failing spec and its logs is a
poor candidate: it would pay write premiums for reads that never come, if it clears the
cacheable minimum at all. Workers added to this module need no pom change; a worker in a new
flow module needs `spring-ai-anthropic` in that module's pom, never in `factory-agents`.

### Parser and TestRail sync

`factory-flow-cypress/.../plan/TestPlanParser.java` and
`factory-flow-cypress/.../plan/DevTeamCatalog.java`, backed by
`src/main/resources/references/dev-teams.md`.

`DevTeamCatalog` maps a team name to TestRail's `custom_dev_team` option id, case-insensitively
and tolerating a trailing `Team`. Both name columns are indexed, so a value spelled the Jira way
(`Bama`) or the TestRail way (`BAMA`) resolves.

Sync distinguishes three outcomes and logs the last two, because an unset field is invisible in
TestRail:

| Outcome | Behaviour |
|---|---|
| Known team with an id | Sets `custom_dev_team`. |
| Known team, no id yet | Warns naming the team; field left unset. Fill the TestRail ID column in `dev-teams.md`. |
| Unknown team | Warns naming the value; field left unset. Add the row if it is a real team. |

The middle case is real: teams appear in Jira before anyone looks them up in TestRail.

`TestPlanParser` owns `ParsedCase`, `ParseState`, `CASE_HEADING`, `ATTR_LINE`, `STEP_ACTION`,
`STEP_EXPECTED`, `parseCasesFromBody` and `flushPendingStep`, and exposes:

```java
public List<ParsedCase> parse(String body);          // slices to "# Test Cases" first (G1)
public record Problem(String caseId, String detail) {}
public List<Problem> validate(List<ParsedCase> cases); // zero cases / zero steps / blank expected
```

`TestRailSyncAgentWorker` takes `test_plan.md` as its only input and reads `issue_key`, `refs`,
`team`, `ecs_story` and `testrail_section` from the frontmatter, falling back to
`properties.testrailSectionId()` for the section. It delegates all parsing to `TestPlanParser`,
and re-asserts `issue_key` inside `refs` before writing, so a Gate 1 amendment cannot orphan a
case from its ticket (Part I §6.3).

### Wiring

`cypress-factory.yaml` is the descriptor in Part I §3.

`CypressFlowConfiguration` declares the beans: `skillWorkspaceService`, `tafSyncService`,
`devTeamCatalog`, `areaDetector`, `contextPrefetch`, `fragmentPrefetch`, `coveragePrefetch`,
`examplesPrefetch`, `testPlanParser`, `promptDump`, and the two worker beans the descriptor
names.

**`HitlRetryService`** (`factory-app`) — made **flow-agnostic** rather than given a cypress
artifact list, because a hardcoded list in the composition root is a control-plane violation.
The carry-over set is derived from the restart step's own declaration:

```java
carryOver = (startStep.inputs() ∪ startStep.optionalInputs()) − {"$trigger", "hitl_feedback.md"}
```

`canRetry(review)` decides whether the button renders, also from the descriptor alone: the review
is PENDING, its step index points at a `HITL_GATE`, and the step before it is an `AGENT` step. No
flow id or gate id appears anywhere in the retry path. Requiring the reviewed step to *be* a gate
is what keeps escalation reviews out — an escalation's step index points at the AGENT step that
failed, not at a gate.

Consequence worth knowing: **any** flow shaped `AGENT → HITL_GATE` now gets the button, including
both Test Factory gates. It restarts and carries artifacts correctly there, but Test Factory's
workers do not declare or read `hitl_feedback.md`, so a retry is a plain re-run rather than a
feedback-driven revision.

**Config** — no new env vars. `FACTORY_CYPRESS_FOLIO_AI_REPO` now drives the skill clone as well
as the GitHub fallback.

## 8. Tests

**Unit — new**

| Class | Covers |
|---|---|
| `TestPlanLintTest` | Each mechanically-decidable rule, every violation paired with the shape the prompt explicitly allows — a false positive here costs a whole regeneration, so the carve-outs are pinned as hard as the catches (fenced blocks and inline code spans for R4, `from X to Y` for R3, `Automation notes` being structurally unreachable for R5) |
| `AreaDetectorTest` | `resolve()` maps router names to real files (case/hyphen tolerant); drops unknown names; falls back to keyword scoring on empty/failed router output; story text beats Jira prefix; two-area spanning; truncates >2 |
| `TestCaseGenAgentWorkerTest` (router group) | 2-line `AREAS`/`WHY` block parsed correctly (no `REFS:` — area detection only); garbage output → keyword fallback, run continues; LLM exception → keyword fallback, run continues |
| `TestCaseGenAgentWorkerTest` (`buildRefs`/`unionRefs` group) | issue key always first and always present for any case — when a case's own refs omit it, list unrelated keys only, list malformed junk (`"the story"`, `"ERM"`, `"123"`), list duplicates in mixed case, or are empty; non-Jira-key values dropped; excluded-project keys dropped and reported separately; `unionRefs` dedupes across multiple cases into the plan-level frontmatter value |
| `TestPlanParserTest` | `# Test Cases` scoping (a `## Notes: x` heading in `# Analysis` must **not** parse as a case); multi-line expected; nested preconditions; validator catches zero cases / zero steps / blank expected |
| `ContextPrefetchTest` | reads from clone; GitHub fallback; classpath fallback; each of the 5 thin-context triggers |
| `SkillWorkspaceServiceTest` | tier resolution clone → GitHub → classpath, and the reported `Source` for each; `isReady()` false before sync; `areaDrift()` flags unroutable context files / known areas without a file; **`requireSkill()` throws when all three tiers fail**; missing context file returns empty (soft) while missing SKILL.md throws (hard); stale-clone-used-when-pull-fails |
| `DevTeamCatalogTest` | all 27 teams parse; name → TestRail id; case-insensitive and `"… Team"`-tolerant lookup; the TestRail spelling resolves where it differs from Jira (`BAMA` → `Bama`); unknown team left untouched rather than guessed; a recognised team with no TestRail id is found but not syncable |

**Unit — worker behaviour**

`TestCaseGenAgentWorkerTest`, driven by `StubChatModel`: fresh run; retry with seeded `test_plan.md` + `hitl_feedback.md`; review pass-first /
pass-second / fail-all-three; feedback accumulation; token accumulation across all calls;
`maxReviewIterations=0`; **plus** malformed-output → format-correction retry → success, and
malformed twice → `AgentExecutionException`.

`TestRailSyncAgentWorkerTest` — metadata mapping and HTML rendering for every TestRail field;
`refs` is computed per case (not read from the frontmatter rollup): a case with no `- **Refs:**`
line → `issue_key` alone; a case whose `- **Refs:**` line omits `issue_key` (e.g. a Gate 1
amendment) → `issue_key` still prepended; two cases with different `- **Refs:**` values get
different TestRail `refs`.

**Unit — frontmatter provenance**

| Class | Covers |
|---|---|
| `TestCaseGenAgentWorkerTest` | Jira's Dev Team overrides a contradicting value the agent wrote; the resolved team is injected into the prompt so the agent need not guess; with no team on the ticket the agent's reading is used; `devTeam()` reads the renamed `devTeam` key in all its single-select shapes (`title`/`value`/`name`/plain) and returns blank rather than throwing when absent |
| `TestCaseGenAgentWorkerTest` | `stripPromotedFields` removes the fenced block, removes bare field lines with no fence, and leaves case blocks below `# Test Cases` untouched |
| `TestRailSyncAgentWorkerTest` | teams the old 10-entry map dropped now resolve (Bienenvolk, Odin, Fenrir, Klemming, Big FC); the TestRail spelling and decorated values resolve |

**Unit — prompt caching**

| Class | Covers |
|---|---|
| `TestCaseGenAgentWorkerTest` | skill + examples ride in the **system** message (only that is cached); `chatOptions()` requests `SYSTEM_ONLY` + `FIVE_MINUTES` with `maxTokens` intact; **the system message is byte-identical across review iterations** — the one test that fails if someone later moves a per-attempt value into `system.md` and silently kills caching |
| `AbstractLlmAgentWorkerTest` | cache write and cache read reach `AgentResult` metrics independently; providers reporting neither produce no cache keys rather than failing; default `chatOptions()` is `null` when `maxTokens()` is unset, so existing overrides are unaffected |
| `EngineMetricsTest` | `cacheWriteTokens`/`cacheReadTokens` become `factory.llm.tokens{type=cache_write|cache_read}`; absent keys record nothing |

No test can prove caching engages against the real API — `StubChatModel` ignores options
entirely. These tests pin the *preconditions* (what is in the system message, that it does not
drift, that the counts are plumbed); Part I §11 step 12 is the empirical check.

**Integration**

A `factory-app` end-to-end mirroring `TestFactoryEndToEndTest`: scripted `StubChatModel`,
WireMock'd Jira/TestRail, run through Gate 1 approval to TestRail sync. Must assert the
**round-trip**: the markdown the stub model emits produces the expected `addCaseWithMetadata`
calls. This is the test that would have caught G1.

Docker must be running (Testcontainers). Full gate: `mvn verify`.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Model drifts from the exact case-block format → silent zero cases | G1–G3. Validator runs on the worker's own output; the step fails loudly rather than syncing nothing. |
| Prompt is ~48–64k input tokens; 3 review iterations ≈ 200k total | Measured acceptable (~$0.60–1.20/run). **Prompt caching is implemented** over the stable SKILL.md + examples prefix (P3), which removes most of the repeat cost inside a run. |
| Router picks a wrong or non-existent area | `AreaDetector.resolve()` drops unresolvable names; total failure falls back to keyword scoring. The generation prompt also tells the agent to flag a mismatched context file in `# Analysis` rather than generate against it — so a bad route is visible at Gate 1 instead of silent. |
| Router adds a call and a failure mode to every run | ~5k tokens / ~$0.02 / ~2s. Every failure path falls back to the old keyword behaviour — the router can degrade the run but never break it. |
| MQA adds a context file that `KNOWN_AREAS` lacks | Startup WARN (`unroutable:<stem>`) from `SkillWorkspaceService.warnOnAreaDrift()`. Without the fix the router cannot select the area at all, not just the keyword fallback. |
| Skill clone unavailable | Three-tier chain per asset (Part I §4). `SKILL.md` is a hard dependency and fails the step rather than degrading; everything else proceeds with a note in `# Analysis`. |
| Bundled classpath copies go stale unnoticed | Source + age printed in `# Analysis` every run; startup WARN on >10% size drift; `scripts/update-cypress-testcase-skill-bundle.sh` run deliberately (Part I §4). |
| `# Analysis` is a section rather than its own artifact, so amending it at Gate 1 means editing one textarea | Fewer fields, not more. Revisit if reviewers complain. |
| Two-area stories double the context file budget (up to 26k tokens) | Cap at 2 areas; second only within 60% of top score. |


## 10. Open decisions

1. **Area table source of truth** — the Java `AREA_KEYWORDS` map is authoritative, with a
   warn-only drift check against SKILL.md's table rather than parsing that table at runtime.
   The map feeds both the router prompt and the keyword fallback, so drift is costly (see `warnOnAreaDrift()`).
2. **Reviewer scope** — the reviewer currently sees only the cases. Consider giving it
   `# Analysis` too so it can flag AC coverage gaps, not just formatting. Defer to a follow-up.
3. **Tool-calling / `claude -p`** — out of scope. The step boundary and artifact contract hold
   regardless of the worker's internals; see Appendix A.

---

## 11. Verification

1. `mvn install -DskipTests` → `mvn verify` green (Docker/Podman up).
2. Trigger `UIMARCAUTH-553` and a Finance/Orders cross-area story.
3. `# Analysis` names the right context file(s) and the router rationale; coverage matrix maps
   every AC. For the cross-area story, confirm the router returned **both** areas.
   Force a router failure (bad API key on that call) and confirm the run completes via the
   keyword fallback with a WARN. (The router only decides area(s) now — see step 7 for `refs`.)
4. Step counts near the House Style median for the area (Inventory ~5, Orders ~8, Fees/Fines ~2).
5. No semicolon-joined field lists; toast text quoted verbatim; absolute numeric values.
6. Frontmatter carries all five sync fields (`issue_key`, `refs`, `team`, `ecs_story`,
   `testrail_section`); `testrail_section` resolves.
   **`team` must equal the story's Jira Dev Team**, never a value inferred from the subject
   matter. With `FACTORY_DEBUG_ENABLED=true`, the exact value read off
   the ticket is in the first `generate-attemptN.md` file under `debugPrompts/<executionId>/`
   (numbering shifts depending on whether coverage-triage nominated candidates) as
   `**Development Team:** <value>`; compare it against the frontmatter and against Jira. There
   must be **no second `---` block** in the body duplicating these fields.
   Watch the log for `is not in references/dev-teams.md` or `has no TestRail id` — either means
   TestRail's Dev Team will be left unset, and both name the team so the table can be corrected.
7. Gate 1 → Approve → cases land in the correct TestRail section with correct metadata.
   Open a created case in TestRail and confirm the **References** field contains the originating
   ticket key, plus any extra keys *that specific case* declared via its own `- **Refs:**` line
   (delta 6) — a case that specifically regression-covers a linked Bug should cite it; most cases
   should show the originating ticket only. Two cases in the same plan may legitimately have
   different References values.
8. Gate 1 → Reject and Re-generate with feedback → retry starts at `testcase-gen`, reuses the
   seeded plan, addresses the feedback.
9. Zero-credentials run completes end-to-end with `CONNECTOR_SKIPPED` audit entries. The skill
   repo is public, so this still resolves via clone or unauthenticated API — the bundle is
   exercised separately in step 10.
10. Degradation matrix (Part I §4): delete the skill clone and block GitHub →
    run still completes off the classpath bundle, `# Analysis` flags the source.
    Then remove the bundled `SKILL.md` too → step **fails** rather than generating.
    Point `AreaDetector` at an area with no context file → run completes with the
    "User Story alone" note. Delete the TAF clone → cases generate, automation notes degrade.
11. **Quality gate:** side-by-side a factory-generated plan against an MQA-generated plan for the
    same story. This is the only test that matters.
12. **Prompt caching (empirical — no test can cover it).** In `/audit`, open the
    `STEP_COMPLETED` event for `testcase-gen` and read the metrics; the same counts are on
    `factory.llm.tokens{type=cache_write|cache_read}`.
    - A run whose review loop iterates should show `cacheWriteTokens` on the first generation
      call and `cacheReadTokens` on the later ones. Read-but-no-write on the first call means
      an earlier run's prefix was still live.
    - `cacheWriteTokens` every call with `cacheReadTokens` never — caching is not engaging.
      Most likely the system prompt is not byte-identical between calls: diff the two
      `generate-attemptN.md` files under `debugPrompts/<executionId>/` above the first `## `
      user section (each file's prompt is above its `# ===== RESPONSE =====` marker, response
      below it).
    - Neither count present at all — the provider is not Anthropic, or the options were
      dropped; check the log for `"is not supported ... Ignoring."`.
    - **Reject and Re-generate is the interesting case.** The retry is a new execution, so its
      first generation call hits only if it lands within five minutes of the previous run's
      last generation call. The pipeline consumes part of that window at both ends (the final
      review call before the gate, then the router call and prefetch after it), leaving roughly
      three to four minutes of actual review time. Consistent misses here are the evidence for
      switching to `ONE_HOUR`.


---

# Part II — Shared reference

Applies to both halves of the flow.

---

## TAF AI Assets Inventory

Before building anything, understand what already exists in `stripes-testing`:

| Asset | Location | What it does |
|---|---|---|
| `CLAUDE.md` | `stripes-testing/CLAUDE.md` (= `copilot-instructions.md`) | Master spec-writing contract: patterns, permissions, ECS rules, interactors, tagging — the single authoritative source of truth |
| `test-planner` agent | `.github/agents/test-planner.agent.md` | Fetches TestRail case, finds similar specs in codebase, produces a step-by-step implementation plan |
| `test-implementer` agent | `.github/agents/test-implementer.agent.md` | Generates the `.cy.js` file from the plan |
| `test-reviewer` agent | `.github/agents/test-reviewer.agent.md` | Validates the generated spec, runs `npx cypress run` |
| `ecs-user-setup` skill | `.github/skills/ecs-user-setup/SKILL.md` (mirrored in `.claude/skills/`) | Authoritative multi-tenant user setup rules — must be consulted for any ECS test (`- **ECS:** Yes`) |
| `review-user-test-edits` skill | `.github/skills/review-user-test-edits/SKILL.md` | Learns from manual corrections and propagates patterns forward in the same session |
| TestRail MCP server | `scripts_for_work/testrail-mcp-server/index.js` | Exposes `get_test_case` tool with automatic section hierarchy retrieval |
| `.claude/settings.local.json` | `.claude/settings.local.json` | Pre-approves MCP + ESLint calls without user prompts |


## MQA Skill Assets Inventory

MQA's `write-testrail-cases` skill, used for manual TestRail case authoring, is the second key
source for the factory. Located at `folio-eureka-ai-dev\skills\write-testrail-cases\`.

| Asset | Location | What it does |
|---|---|---|
| `write-testrail-cases` skill | `SKILL.md` | Mandatory 7-step workflow for writing FOLIO TestRail cases: area detection, scenario analysis format, writing guidelines (title format, step granularity, assertion style), self-review gate, TestRail metadata fields, API posting |
| Area context files (19) | `references/context/*.md` | Per-module business rules, exact verbatim UI texts (toasts, modal titles, errors), UI structure (panes, accordions), capability sets, verification patterns — the factual grounding the LLM needs to write correct FOLIO test cases |

**Important distinction:** All TAF and MQA assets are headless-Java-incompatible by design (they
rely on IDE tooling, MCP servers, interactive feedback). The factory carries their *logic and
instructions* as content, not the asset files themselves.

**Skill assets are runtime-loaded, not embedded:**
`SKILL.md`, `references/examples.md` and `references/context/*.md` are read at execution time
through the three-tier chain in Part I §4 — local clone first, GitHub API second, the jar bundle
last. When MQA edits a context file or the skill itself, the factory picks the change up on the
next run with no re-sync. Only the factory delta block (Part I §7) is authored here.

**Ongoing maintenance consequence:** When the TAF's `CLAUDE.md`, `ecs-user-setup/SKILL.md`, or
the MQA `write-testrail-cases/SKILL.md` writing guidelines are updated, the corresponding
factory system prompts must be manually re-synced. Context files (`references/context/*.md`) are
exempt — they auto-sync via runtime load. Recommend a short checklist mapping which source file
maps to which factory prompt section, reviewed whenever a source asset changes.

---


## Who Does What — Plain English

Before reading the implementation details, it helps to understand who is responsible for each
piece and when that work happens.

### The three types of input an LLM agent needs

Every LLM agent in the factory has two inputs: a **system prompt** (the agent's standing
instructions — what it is, what rules it follows, what format it must produce) and a
**user prompt** (the per-run data — the specific Jira story, the test plan for this execution,
the fragment index for this run).

```
System prompt  — fixed instructions, same for every run
User prompt    — variable data assembled fresh each run from this execution's artifacts
```

### Who writes the system prompts — a developer, once

The system prompts are plain `.md` files checked into the factory repo:

```
factory-flow-cypress/src/main/resources/prompts/cypress-testcase-gen/system.md
factory-flow-cypress/src/main/resources/prompts/cypress-area-router/system.md
factory-flow-cypress/src/main/resources/prompts/cypress-coverage-triage/system.md
factory-flow-cypress/src/main/resources/prompts/cypress-testcase-reviewer/system.md
factory-flow-test-automation/src/main/resources/prompts/cypress-test-framework-explorer/system.md   (Part III)
factory-flow-test-automation/src/main/resources/prompts/cypress-automate-test-case/system.md         (Part III)
factory-flow-test-automation/src/main/resources/prompts/cypress-review-automated-test-case/system.md  (Part III)
```

**A developer writes these files during implementation** by reading the source assets (MQA's
`write-testrail-cases/SKILL.md`, the TAF's `CLAUDE.md`, `ecs-user-setup/SKILL.md`) and copying
the relevant sections verbatim into the factory prompt files. This is the extraction work
described in sections 4 and 5 below.

Once written, the factory just reads these files off disk on every run — no code involved,
no dynamic assembly. If a source asset changes significantly, a developer re-syncs the
corresponding `system.md` by hand.

> **For MQA engineers:** The factory delta block in `cypress-testcase-gen/system.md` is your
> job — you know the
> `write-testrail-cases` skill best. It is literally copy-paste from SKILL.md plus a few lines
> specifying output format. No Java required.

> **For AQA engineers:** the three Part III prompts (explorer, automate, review) are yours (Ostap
> wrote them from the TAF's `CLAUDE.md`). The pipeline appends run-specific notes from code (the
> case's dev team, the author's automation notes, gate 2 feedback, whether specs may be run), so
> the prompt files stay plain.

### Who assembles the user prompts — the factory, every run

The user prompt templates (`user.md` files) contain placeholders like `{{issue}}`,
`{{context_files}}`, `{{fragments}}` and `{{coverage}}`. The worker fills these at runtime from
what it fetched and prefetched. A developer writes the template once; the factory assembles it
on every run.

### What is runtime-loaded vs embedded

| What | How it reaches the LLM | Who keeps it current |
|---|---|---|
| `system.md` files | Embedded at implementation time | Developer re-syncs when source changes |
| `SKILL.md`, `examples.md`, area context files | Read at runtime by `SkillWorkspaceService` / the prefetch services | MQA — an edit takes effect on the next factory run |
| TAF fragments and permissions | Scored at runtime by `FragmentPrefetch` | Reflects `stripes-testing` at the last clone or pull |
| `test_plan.md` | Produced by the generation step in the same execution | Generated fresh each run |

### The three types of components you write

| Type | How to spot it | YAML entry? | Example |
|---|---|---|---|
| **Non-LLM worker** | Java class `implements AgentWorker` directly | Yes — `type: AGENT` step in the YAML | `TestRailSyncAgentWorker` |
| **LLM worker** | Java class `extends AbstractLlmAgentWorker` | Yes — `type: AGENT` step in the YAML | `TestCaseGenAgentWorker` |
| **Plain service** | Java class, no SPI | No — called directly by a worker | `ContextPrefetch`, `DevTeamCatalog` |
| **Post-processor** | Java class annotated `@Component implements StepPostProcessor` | **No** — auto-runs after every agent step, no YAML entry needed | `CypressSanityPostProcessor` |

**Why "agent" and "worker" both appear:** In the YAML, every pipeline step (LLM or not) has `type: AGENT` — that is just the YAML vocabulary for "a step that runs a worker." In Java, the interface is `AgentWorker`. So "worker" and "agent" refer to the same thing from different angles. The `-Agent` or `-Worker` suffix in a class name is just a naming convention and does not tell you whether it calls an LLM — only the supertype does. `TestRailSyncAgentWorker` has "Agent" in its name but is non-LLM; `TestCaseGenAgentWorker` has "Agent" and is LLM. Always check the supertype.

---


## What You Build

### 1. New Maven module: `factory-flow-cypress`

Mirror `factory-flow-test-factory`. One configuration class, one flow descriptor, workers and prompts.

```
factory-flow-cypress/
  pom.xml
  src/main/java/org/folio/factory/cypressfactory/
    CypressFlowConfiguration.java
    CypressFlowProperties.java
    plan/
      TestPlanParser.java                   (parses + validates the "# Test Cases" slice)
      DevTeamCatalog.java                   (team name -> TestRail option id)
    prefetch/
      AreaDetector.java                     (validates router areas; keyword fallback)
      ContextPrefetch.java                  (area context file + thin-context enrichment)
      FragmentPrefetch.java                 (TAF fragment scoring + permissions)
      CoveragePrefetch.java                 (existing TestRail cases for the area)
      ExamplesPrefetch.java                 (golden examples)
    worker/
      SkillWorkspaceService.java            (skill clone + three-tier asset reads)
      TafSyncService.java                   (stripes-testing clone)
      PromptDump.java                        (rendered-prompt + response capture, debug only)
      TestCaseGenAgentWorker.java           (LLM: router call, generation, review loop)
      TestRailSyncAgentWorker.java          (non-LLM: push cases, write real ids back)
  src/main/resources/
    flows/cypress-factory.yaml
    references/
      SKILL.md                              (bundled last-tier copy)
      examples.md                           (bundled last-tier copy)
      dev-teams.md                          (27 FOLIO teams, Jira <-> TestRail)
    prompts/cypress-area-router/
      system.md
      user.md
    prompts/cypress-testcase-gen/
      system.md
      user.md
    prompts/cypress-coverage-triage/
      system.md
      user.md
    prompts/cypress-testcase-reviewer/
      system.md
      user.md
```

### 2. Automation module: `factory-flow-test-automation` (Part III)

```
factory-flow-test-automation/
  src/main/java/org/folio/factory/autotests/
    TestAutomationFlowConfiguration.java / TestAutomationFlowProperties.java
    cli/        CodingAgentCli.java, ClaudeCodeCli.java
    workspace/  AutomationWorkspace.java, PreCommitChecks.java, RunEnvironments.java
    worker/     AutomationSelectAgentWorker.java, AutomateCasesAgentWorker.java,
                AutomationPublishAgentWorker.java, DevTeamNames.java,
                CypressTestFrameworkExplorerAgentWorker.java (Ostap),
                CypressTestAutomationGenerationAgentWorker.java (Ostap),
                CypressTestAutomationReviewAgentWorker.java (Ostap),
                CypressDraftTestAutomationAgentWorker.java (Ostap, unused)
  src/main/resources/
    cypress-run-environments.json           (ecs / non-ecs FOLIO envs for spec runs)
    prompts/cypress-test-framework-explorer/, cypress-automate-test-case/,
            cypress-review-automated-test-case/, cypress-draft-automate-test-case/
```

---


## What the flow reuses

| Component | Source | Role here |
|---|---|---|
| HITL gate mechanism, `ArtifactStore`, `ExecutionEngine`, poller | `factory-core` | Unmodified control plane. |
| HITL web console and review UI | `factory-app` | Gate 1 is a standard review page; `reference_prompts` and the block-scalar instructions are descriptor data, not cypress-specific UI. |
| `HitlRetryService` | `factory-app` | Reject-and-re-generate. Flow-agnostic: the carry-over set comes from the restart step's declared inputs. |
| `optional_inputs` | `factory-core` (`StepDescriptor`) | Lets the generation step read `test_plan.md` and `hitl_feedback.md` when a retry seeded them, and ignore them on a fresh run. |
| `AbstractLlmAgentWorker`, `FrontmatterCodec`, `PromptLoader` | `factory-agents` | Prompt loading, frontmatter render/parse, token and cache metrics. |
| TestRail connector | `factory-connectors` | Case creation, label resolution, section-path resolution, existing-coverage reads. |
| Jira connector | `factory-connectors` | Issue and linked-issue fetch, including the `devTeam` custom field. |
| GitHub connector | `factory-connectors` | Second-tier asset fetch (`getFileContent`, `listDirectory`) and context enrichment. |
| `write-testrail-cases/SKILL.md` | MQA skill repo | Loaded at runtime as the generation system prompt. Never forked. |
| `references/examples.md` | MQA skill repo | Loaded at runtime as the few-shot style anchor. |
| Area context files (38 × `references/context/*.md`) | MQA skill repo | Loaded at runtime for the detected area. |
| TAF fragments and `permissions.js` | `stripes-testing` | Scored and excerpted for automation notes and the `Automatable` flag. |

Part III also uses the GitHub connector's `syncFork` / `createPullRequest` / `findOpenPullRequest`
for the PR step, the `claude` CLI for the coding sessions, and the TAF's own tooling
(`lint-staged`, `environments.js`, `cypress run`).

## Environment Variables

| Variable | Purpose |
|---|---|
| TAF path | Auto-managed: `TafSyncService` clones to `{user.dir}/cypressTafClone` on startup — no env var needed |
| `FACTORY_CYPRESS_FOLIO_AI_REPO` | GitHub repo (`owner/name`) containing the FOLIO AI skills. Cloned to `skillsClone/` at startup by `SkillWorkspaceService`; the GitHub API and the jar-bundled copies are the second and third fallbacks. Supplies SKILL.md, `examples.md` and the area context files. |
| `FACTORY_CYPRESS_TARGET_REPO` | Repo (`owner/name`) where the PR step opens pull requests - a **fork** of stripes-testing; the GitHub token needs push access. Unset = no PR. |
| `FACTORY_CYPRESS_BASE_BRANCH` | PR base branch in the target repo (default `master`) |
| `FACTORY_CYPRESS_TESTRAIL_SECTION_ID` | Fallback TestRail section ID (long integer); used when `testrail_section` is absent from `test_plan.md` frontmatter, or present but unresolvable against TestRail |
| `FACTORY_CYPRESS_TESTRAIL_CASE_TYPE_NAME` | TestRail case type name, resolved against `get_case_types` at sync time (not a hardcoded id - those drift); code default `Functional` |
| `FACTORY_CYPRESS_TESTRAIL_PRIORITY_NAME` | TestRail priority name, resolved against `get_priorities` the same way; code default `Medium` |
| `FACTORY_CYPRESS_TESTRAIL_LABEL` | Label title applied to every created case (default: `cypressFlow-delete`); resolved to a label ID at runtime via `getOrCreateLabel` |
| `FACTORY_CYPRESS_MAX_REVIEW_ITERATIONS` | Max agent review iterations inside `TestCaseGenAgentWorker` per run (default `3`). Set to `0` to skip automated review entirely. |
| `FACTORY_CYPRESS_LLM_TIMEOUT` | Per-request LLM call timeout for this flow (default `900s`). Overrides the shared `FACTORY_LLM_TIMEOUT` — applied via `AnthropicChatOptions.Builder.timeout()`, which Spring AI uses to build a separate client keyed on client-level settings, so the per-flow value always wins. Do not set it too low: a client-side cancel stops neither the server's work nor the billing, so a timeout firing on a slow-but-valid call pays full price for output it discards. |
| `FACTORY_CYPRESS_SEND_IMAGES` | Send images embedded inline in the story description to the model (default `true`). Set `false` if `FACTORY_LLM_MODEL` points at a model without image support — such a model **rejects** the call outright rather than ignoring the attachment. |
| `FACTORY_CYPRESS_REFS_EXCLUDED_PROJECTS` | Comma-separated Jira project keys never added to any case's `refs` (default `FAT`). The originating ticket is always kept even if its own project is excluded. Enforced twice: the generation prompt is told upfront not to cite these projects (Provenance block), and `buildRefs()` drops them again, silently, at Assemble/sync time as a backstop — unlike the old router-time design, a drop here is not echoed back into `# Analysis`, since generation has already finished by the time sync applies it. |
| `FACTORY_CONNECTORS_TESTRAIL_BASE_URL` | TestRail instance base URL |
| `FACTORY_CONNECTORS_TESTRAIL_USERNAME` | TestRail username (email) |
| `FACTORY_CONNECTORS_TESTRAIL_API_KEY` | TestRail API key |
| `FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID` | TestRail project ID (integer) |
| `FACTORY_CONNECTORS_TESTRAIL_SUITE_ID` | TestRail suite ID (integer; required on multi-suite projects) |
| `FACTORY_CONNECTORS_GITHUB_TOKEN` | GitHub token: reads for asset fetch and enrichment; push to the target fork and PR creation for the PR step |
| `FACTORY_CONNECTORS_GITHUB_BASE_URL` | GitHub API base URL (default: `https://api.github.com`; override for GHE) |
| `FACTORY_DEBUG_ENABLED` | Dumps every rendered prompt, with the model's raw response appended below it, to `debugPrompts/<executionId>/`, and exposes `POST /api/debug/partial-run`. Local use only. |
| `FACTORY_CYPRESS_AUTOMATION_ENABLED` | Default for runs that don't send `"automate"` (default `true`) |
| `FACTORY_CYPRESS_AUTOMATION_MAX_CASES` | Cases automated per run; the rest are listed as skipped (default `2`) |
| `FACTORY_CYPRESS_AUTOMATION_RUN_SPECS` | `true` = the review session runs each spec on the case's ECS / non-ECS env and fixes it up to twice (default `false`; not yet verified end to end) |
| `FACTORY_CYPRESS_AUTOMATION_RUN_ENVS` | JSON with `non-ecs` and `ecs` profiles replacing the bundled spec-run environments |
| `FACTORY_CYPRESS_AUTOMATION_CLI_MODEL` / `_CLI_EFFORT` | claude CLI `--model` / `--effort` (defaults `claude-sonnet-5-5` / `high`); never taken from the host's `~/.claude/settings.json` |
| `FACTORY_CYPRESS_AUTOMATION_TIMEOUT_SECONDS` | Per claude CLI session (default `1800`) |
| `FACTORY_CYPRESS_AUTOMATION_WORKSPACE` | The clone automation writes to (default `cypressTafWork`) |

---


---

# Part III — Cypress automation (built 2026-11-12)

Steps 3-6 of `cypress-factory`. The code is in `factory-flow-test-automation`: Ostap's explorer,
generation and review workers, driven by a per-case loop. History, decisions and follow-ups are
in [CYPRESS_AUTOMATION_MERGE_PLAN.md](CYPRESS_AUTOMATION_MERGE_PLAN.md). How to run it is in
[QUICKSTART.md](QUICKSTART.md). An earlier design for this part (an automation agent, a separate
execution worker, a finalizer and an Allure report) was never built and has been removed from
this plan.

## 1. Chain

```
[cypress-testrail-sync]   (Part I) also writes testrail_cases.json: created case ids, Automatable
     │                     flag, automation notes, areas, the story's dev team
     ▼
[cypress-automation-select]                                          non-LLM
     │  ── case ids from the trigger (testCaseIds / testCaseId) or else from testrail_cases.json
     │  ── "automate": false in the payload (or FACTORY_CYPRESS_AUTOMATION_ENABLED=false) -> none
     │  ── drops Automatable: No, applies FACTORY_CYPRESS_AUTOMATION_MAX_CASES (rest = skipped)
     │  ── fetches each case from TestRail; team per case (hand-off, or Dev Team id -> name)
     ▼
[automation_cases.json]
     ▼
[cypress-automate-cases]                    LLM (claude CLI), one case at a time, one branch
     │  ── workspace: cypressTafWork/ (own clone; cypressTafClone/ stays read-only for Part I)
     │       branch factory/<issueKey> from fresh origin/master; yarn install once
     │  ── per case: explorer (file list narrowed to the area) -> generate (claude CLI)
     │       -> [environments.js with the case's ECS / non-ECS profile, if RUN_SPECS]
     │       -> review (claude CLI; with RUN_SPECS also runs the spec and fixes it, at most twice)
     │       -> lint-staged (prettier --write + stripes-lint --fix: the TAF pre-commit hook)
     │       -> commit (one per case; a failed case's changes are discarded, the loop goes on)
     ▼
[automation_report.md, automation_results.json]
     ▼
[gate-2-review]                                                      HITL
     ▼
[cypress-automation-publish]                                         non-LLM
     │  ── sync the fork (FACTORY_CYPRESS_TARGET_REPO) with upstream
     │  ── git push of the run's branch (token as an http extraHeader via GIT_CONFIG_* env)
     │  ── open the PR via the GitHub API
     ▼
[automation_publish.json]  (PR url; skipped without a target repo or token)
```

## 2. Ways to run

| Mode | How |
|---|---|
| Full | manual trigger with `issueKey` |
| Cases only | `"automate": false` in the payload, or `FACTORY_CYPRESS_AUTOMATION_ENABLED=false`. Gate 2 opens with "Automation disabled"; approve to finish. |
| Automation only | `POST /api/debug/partial-run` at `startStepId: automation-select` with `triggerPayload: {"testCaseIds": [...], "areas": [...]}` (`areas` optional) |

## 3. Components (`factory-flow-test-automation`)

| Class | Role |
|---|---|
| `AutomationSelectAgentWorker` | Selection step (above). Replaces Ostap's `cypress-testrail-get-test`. |
| `AutomateCasesAgentWorker` | The loop, the per-case commits, and the gate 2 report. Reads the reviewer's closing JSON verdict, whatever its key names; the spec path falls back to the case's commit. |
| `CypressTestFrameworkExplorerAgentWorker`, `CypressTestAutomationGenerationAgentWorker`, `CypressTestAutomationReviewAgentWorker` | Ostap's workers and prompts. Each has a plain method (`explore` / `automate` / `review`) the loop calls. |
| `AutomationPublishAgentWorker` | PR step. |
| `AutomationWorkspace` | The work clone: branch per run, commit, discard, push, yarn install, lint-staged. All git calls run without hooks or a credential helper. |
| `PreCommitChecks.LintStaged` | What the TAF's husky pre-commit hook does, run explicitly before each commit. |
| `RunEnvironments` | The two FOLIO environments for spec runs (`cypress-run-environments.json`, etesting snapshot diku / consortium). |
| `ClaudeCodeCli` | `claude --print` with a pinned model and effort, a tool allowlist, a timeout, and `cmd /c` only on Windows. |
| `DevTeamNames` | TestRail Dev Team id -> name, from Part I's `references/dev-teams.md`. |

Part I's `cypress-factory.yaml` references these worker ids. There is no Maven dependency between
the two modules: descriptors are validated against every registered worker in `factory-app`.

## 4. Status and known limits

- Built and run for real end to end (UIU-3562, UIOR-1492): cases -> gate 1 -> sync -> two specs
  with one commit each -> gate 2 -> PR on the fork. Lint was clean, and a from-scratch clone and
  install worked.
- **Spec runs (`RUN_SPECS=true`): implemented, not verified end to end; off by default.** A slow
  environment (60 s `pageLoadTimeout`) makes specs fail for reasons unrelated to the spec.
- Gate 2 **Reject and Re-generate** starts over from fresh master; the model does not see its
  previous specs or findings. A fix is designed (carry the previous results, branch from the
  previous head).
- Lint errors that `--fix` can't fix are reported at gate 2, not fixed. Fixing them with the model
  inside the spec-gen loop, and opening a draft PR when problems remain, is after-demo work.
- The specs follow the case's dev team, but capability-set names and UI locators the TAF doesn't
  know yet are guesses, which the reviewer reports as such.

---

# Appendix A — Alternatives considered and rejected

Both were viable and neither was rejected on quality grounds. Idea 3 won on effort-to-first-measurement: it tests the core hypothesis ("fragmentation is the problem") without betting on tool-loop behaviour or an external process.

**These stay cheap to revisit.** The step boundary and the artifact contract (Part I §6) are identical whichever of the three is used — only the internals of `TestCaseGenAgentWorker` change. A switch touches one class, not the flow, not the gate, not TestRail sync.

### Idea A — spawn `claude -p` as the generation worker

The worker prepares a workspace (skill clone + TAF clone + `task.md` carrying the Jira issue), runs `claude -p "<task>" --output-format json`, reads back the artifact files Claude wrote, and
returns them as an `AgentResult`.

**Why it wins:** it *is* the MQA process — same model, same tools, same skill file, same session semantics. Zero re-implementation. When MQA updates SKILL.md we inherit it for free. Highest
quality-per-effort by a wide margin.

**Cost:** external process dependency (CLI installed and authenticated on whatever runs the factory); bypasses the factory's prompt catalog, model config, and token metrics (though
`--output-format json` returns usage); unit tests need a fake CLI on PATH. Stretches "stateless worker", though the `AgentWorker` SPI still holds.

### Idea B — one tool-calling Spring AI worker

One `AGENT` step. The `ChatClient` gets filesystem tools scoped to the skill + TAF workspace (`read`, `grep`, `glob`) plus connector tools (`fetchJiraIssue`, `fetchLinkedIssues`,
`listTestRailSections`, `readSectionCases`). The system prompt is thin — "you are running the write-testrail-cases skill, start by reading SKILL.md" — and the agent drives the workflow
itself: detect area → read context file → read examples → map rules → scenarios → cases.

**Why it wins:** faithful to the adaptive part — it can read two context files when a story spans Finance and Orders, or grep the TAF for a fragment it just thought of. Stays entirely in-stack: prompt catalog, metrics and model config all keep working.

**Cost:** hand-roll the tool classes (Spring AI 2.0.0 ships no `FileSystemTools`/`GrepTool`/ `GlobTool` — see Part I §5); the multi-turn loop is slower and less predictable; needs a tool-call cap
to avoid runaway loops. Real risk: the model wanders or under-fetches without the interactive human nudges MQA gives it in a live session.

### When to reopen

| Signal | Move to |
|---|---|
| Cases are good but the area/context choice is often wrong on cross-area stories | **B** — adaptive fetching is the specific gap |
| Cases still lag MQA's on the same story after prompt tuning | **A** — stop re-implementing a process that already works |
| MQA changes SKILL.md often enough that our delta block keeps drifting | **A** |
| Quality is fine and cost/latency is the complaint | Stay on Idea 3. Prompt caching is already in place; the next lever is the one-hour cache TTL (P3). |
