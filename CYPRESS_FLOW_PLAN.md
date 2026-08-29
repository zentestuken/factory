# Cypress Factory Flow — Implementation Plan

**Story → Manual Test Cases → Cypress E2E specs, using TAF AI assets and MQA skill assets**

This is the single plan for the flow. It covers what is built and running (Part I), the shared
reference material both halves need (Part II), and the Cypress-automation phases still ahead
(Part III).

---

## Status at a glance

| | Scope | State |
|---|---|---|
| **Part I** | Story → manual test cases → TestRail, behind one QA gate | Implemented. 3 steps, 2 artifacts, 1 LLM worker. |
| **Part II** | Asset inventories, roles, env vars, reuse | Reference for both halves. |
| **Part III** | Test cases → Cypress specs → PR → execution → Allure report | Not implemented. Design only. |

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
          SKILL.md · references/examples.md · references/context/*.md  (29 area files)

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
     │      ── SKILL.md steps 1 + 3 are AGENT JUDGMENT, not keyword matching:
     │           "the story itself is the source of truth"; story text outranks the
     │           Jira project prefix; refs = what the case verifies (may be a Bug,
     │           a Tech-Debt ticket, or several keys across projects)
     │      ── input: raw issue + linked issues + the 29-area table lifted from SKILL.md
     │      ── returns: areas[1–2] · refs · one-line detection rationale
     │      ── AreaDetector validates the names against the 29 real context files;
     │           unknown / empty / call failure → keyword-score fallback
     │      ── output is 4 small FACTS, not a summary — the mega-call still receives
     │           the raw issue and re-reasons with everything visible
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
     │    cypress-testcase-reviewer scores the "# Test Cases" slice against R1–R11
     │      ── FAIL → feedback + previous plan appended → regenerate (C) → revalidate (D)
     │      ── PASS or attempts exhausted → verdict written into "# Review"
     │
     │ ── F · ASSEMBLE ──────────────────────────────────────────── non-LLM
     │
     │    frontmatter written by the WORKER, never trusted from the LLM:
     │      issue_key         ← $trigger
     │      refs              ← issue_key ALWAYS FIRST + router extras (union, deduped)
     │      team              ← Jira dev-team field (AUTHORITATIVE, canonicalised
     │                          via DevTeamCatalog) · else agent · else ""
     │      ecs_enabled       ← agent · else false
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
     │  ── automation_type=Manual · labels=[AI label] · team · ecs · refs (from frontmatter)
     │  ── collect real C###### IDs per case
     ▼
[test_plan.md]  — TC-NNN tokens in body headings replaced with real C###### IDs
     ▼
[downstream: Cypress automation flow — out of scope for this plan]
```

## 3. Flow descriptor

`factory-flow-cypress/src/main/resources/flows/cypress-factory.yaml`

```yaml
id: cypress-factory
name: Cypress Factory
version: 2.0.0
triggers:
  - event_type: manual
input_schema:
  required: [issueKey]
output_schema:
  artifacts: [test_plan.md]
retry_policy:
  max_attempts: 3
  backoff_seconds: [30, 120, 300]
agent_chain:
  - step_id: testcase-gen
    type: AGENT
    worker_id: cypress-testcase-gen-agent
    inputs: ["$trigger"]
    optional_inputs: [test_plan.md, hitl_feedback.md]
    outputs: [test_plan.md]

  - step_id: gate-1-review
    type: HITL_GATE
    gate:
      gate_id: gate-1-test-cases
      title: "QA review: test cases"
      review_instructions: |
        Everything is in test_plan.md, in three sections. Read them in this order:

        - "# Analysis" — the area that was detected and why, the context file that was
          loaded, the Key Business Rules the story touches, and the AC to scenario coverage
          matrix. Read this first: it tells you whether the SCOPE is right, which is the one
          thing no automated check can judge.
        - "# Test Cases" — exactly what will be posted to TestRail, nothing else.
        - "# Review" — the automated reviewer's verdict.

        (Abridged. The descriptor holds the full block scalar, which also covers the four
        frontmatter fields and the degradation warning. UiFormat.instructionBlocks renders
        blank-line-separated paragraphs and "- " runs as real paragraphs and lists.)
      reviewed_artifacts: [test_plan.md, hitl_feedback.md]
      reference_prompts: [cypress-testcase-reviewer]

  - step_id: testrail-sync
    type: AGENT
    worker_id: cypress-testrail-sync
    inputs: [test_plan.md]
    outputs: [test_plan.md]
```

**3 steps. 1 HITL gate. 2 artifacts.**

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
| TAF fragments | `automationNotes`, `automatable` flag | SOFT. Test case **content** is unaffected — only automation hints. `# Analysis` notes it so the Gate 1 reviewer knows to check the flags (the gate already says "override automatable flags"). |

**Bundle scope: `SKILL.md` + `examples.md` only** (~100 KB), under
`factory-flow-cypress/src/main/resources/references/`.

- Not the 29 context files (890 KB). Their absence has a documented graceful path, and checking
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
| `AreaDetector` | No | **Validator + fallback** for the router call: resolves returned area names against the 29 real context files; keyword-scores story text + Jira prefix when the router fails or returns something unknown. |
| `ContextPrefetch` | No | Reads area context file(s) from the skill clone; runs the 5 thin-context triggers; enriches from GitHub/Jira when triggered. |
| `FragmentPrefetch` | No | Lists + keyword-scores TAF fragments for the detected area. |
| `CoveragePrefetch` | No | Area-matched TestRail sections → existing cases; dedup by case id, cap 150. |
| `ExamplesPrefetch` | No | Reads `references/examples.md` from the skill clone; GitHub then classpath fallback. |
| `DevTeamCatalog` | No | The 27 FOLIO teams, parsed from `references/dev-teams.md`. Used by **both** workers, like `TestPlanParser`: the generator canonicalises the team it read off the ticket, sync turns that name into TestRail's `custom_dev_team` option id. Data lives in a resource so filling in a new team is an edit, not a release. |
| **`TestCaseGenAgentWorker`** | **Yes** | Fetches the Jira issue, runs the router call, assembles the mega-prompt, one call → markdown plan, then the reviewer loop. |
| ↳ router call | Yes | SKILL.md steps 1 + 3 judgment: which area(s), what `refs`. ~5k in / ~200 out. |
| ↳ reviewer loop | Yes | Existing pattern, same worker, `maxReviewIterations` (default 3, 0 = off). |
| ↳ `TestPlanValidator` | No | Parses the agent's own output before returning. One format-correction retry, then fail the step. |
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
ecs_enabled: false
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
| `refs` | Issue key first, always, then any Jira keys the router proposed (regex-filtered). |
| `team` | **Jira's Dev Team field is authoritative** — `customfield_10057`, which `JiraRestConnector` renames to `devTeam`. Canonicalised through `DevTeamCatalog`. The agent's reading is used only when the ticket has no team, because it has no other source and would otherwise infer one from the subject matter. |
| `ecs_enabled` | The agent, from labels and story text. |
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
| `refs` | **`issue_key` always, plus** router extras (SKILL.md step 3 judgment — a Bug key the case regression-covers, a Tech-Debt ticket, related keys across projects) | `issue_key` alone |
| `team` | **Jira dev-team field first**, else the agent's reading; canonicalised via `DevTeamCatalog` | `""`, and sync logs a warning naming the team rather than dropping it silently |
| `ecs_enabled` | agent | `false` |
| `testrail_section` | agent, **validated via `testRail.resolveSectionByPath()` before writing** | config default `FACTORY_CYPRESS_TESTRAIL_SECTION_ID`; note added to `# Analysis` |
| `case_count` | validator | — |
| `review_passed`, `review_iterations` | reviewer loop | — |

**`refs` — the ticket key is non-negotiable.** `refs` is TestRail's **References** field; a case
that loses its originating ticket is orphaned. The router is therefore purely additive:

```java
// worker, phase F
List<String> refs = new ArrayList<>();
refs.add(issueKey);                                   // always first
for (String extra : routerRefs) {
    String k = extra.strip().toUpperCase(Locale.ROOT);
    if (JIRA_KEY.matcher(k).matches() && !refs.contains(k)) refs.add(k);
}
// JIRA_KEY = Pattern.compile("[A-Z][A-Z0-9]*-\\d+")
```

The regex filter drops anything that is not a well-formed Jira key, so a hallucinated or
malformed router value is discarded rather than written into TestRail. `TestRailSyncAgentWorker`
re-applies the same guarantee when reading the frontmatter (a reviewer could delete the key while
amending at Gate 1), so `issue_key` is present in `refs` by construction at both layers.

### 6.4 What the sync worker writes into TestRail

Every value comes from `test_plan.md` — the frontmatter for case-wide fields, the parsed case
block for per-case fields.

| TestRail field | Value source | Notes |
|---|---|---|
| `section_id` | `testrail_section` from the frontmatter | Path string (separators `›` `|` `>` `→`, resolved via the API) or a numeric id. Falls back to `FACTORY_CYPRESS_TESTRAIL_SECTION_ID` when absent or unresolvable. |
| `custom_test_group` | `Test Group` on the case block | 1 = smoke, 2 = criticalPath, 3 = extendedPath |
| `custom_dev_team` | `team` from the frontmatter, mapped through `DevTeamCatalog` | Left unset when the team is unknown or has no TestRail id; both cases log a warning naming the team. |
| `custom_ecs_enabled` | `ecs_enabled` from the frontmatter, OR the per-case `ECS` flag | Either one being true sets it. |
| `custom_automation_type` | `2` (Manual) for every case | The Cypress spec is the automation artifact; the TestRail case stays a manual record. |
| `labels` | `[id of testrailLabel]` | Resolved at runtime via `getOrCreateLabel`; title from `FACTORY_CYPRESS_TESTRAIL_LABEL`, default `cypressFlow-delete`. |
| `refs` | `refs` from the frontmatter | The originating `issue_key` is re-asserted here even if a Gate 1 amendment removed it. |
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
stays unreplaced in the body, and a downstream consumer would read it as a case id. See the
Part III risks.

### Section hierarchy for `describeBlocks`

After creating each case, fetch the assigned section hierarchy the same way the MCP server does:
walk `parent_id` up to `depth == 0` via `GET /api/v2/get_section/{id}`. Store as
`describeBlocks` in the mapping for use in the finalizer PR description (informational only —
the specs already have correct `describe()` nesting from the generation step).

The Java connector will need a `getSection(sectionId)` method added.

---


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
| `AreaDetector` | `resolve(List<String> routerAreas, JsonNode issue) → List<String>`<br>`areaTable() → String` (the 29-row table for the router prompt) |
| `ContextPrefetch` | `load(List<String> areas, JsonNode issue) → ContextBundle(text, enriched, enrichmentNote)` |
| `FragmentPrefetch` | `scan(List<String> areas, String summary) → String` |
| `CoveragePrefetch` | `load(List<String> areas) → String` |
| `ExamplesPrefetch` | `load() → String` |

Notes:
- `AreaDetector` holds `AREA_KEYWORDS`, `PROJECT_KEY_AREAS` and `detectAreas()`. It does not
  choose the area — the router call does. Its two jobs:
  - `resolve(routerAreas, issue)` — map each router-returned name to one of the 29 real context
    files (case-insensitive, hyphen/space tolerant). Any name that does not resolve is dropped
    and logged at WARN. If nothing resolves, or the router call failed, fall back to keyword
    scoring on raw issue `summary` + `description` + `labels` + project key.
  - `areaTable()` — the 29-row Area/context-file/keywords table, rendered for the router prompt.
- Cap at 2 areas (SKILL.md: "A story can span two areas — read both context files"). The router
  is instructed to return at most 2; `resolve()` truncates if it returns more.
- `ContextPrefetch` owns `isThinContext`, `isDraftContext`, `countKeyBusinessRules`,
  `countToastsInUiTexts`, `countKnownGaps`, `enrichGitHub`, `enrichJira`, `fetchPermissions`,
  `fetchTranslations` and `AREA_GITHUB_REPOS`. It reads the context file through
  `SkillWorkspaceService.read("references/context/" + area + ".md")`.
- `AREA_KEYWORDS` area names must match the 29 context filenames exactly
  (`agreements`, `bulk-edit`, `check-in`, …). Log a startup WARN for any area name with no
  matching file, and for any context file with no area entry (drift check against MQA's table).

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
   Returns `areas`, `refs`, and a one-line rationale. `areaDetector.resolve(...)` validates;
   any failure (call error, unparseable, nothing resolves) falls back to keyword scoring —
   **the router must never break the run**.
3. **Prefetch** — context (+ enrichment), examples, fragments, coverage, TestRail section list.
   Every prefetch degrades to a placeholder string on failure; none may throw.
4. **Generate** — `callForText(GEN_PROMPT_ID, vars)`. Free-form markdown; not
   `callForEntity`, and no structured-output schema.
5. **Validate** — `parser.parse(planMarkdown)`. On failure, one retry with a format-correction
   message appended; on second failure throw `AgentExecutionException`.
6. **Review loop** — unchanged shape. Reviewer receives the `# Test Cases` slice. On FAIL,
   append feedback + the previous plan to `vars` and regenerate (re-validating each time).
7. **Assemble** — worker writes frontmatter per Part I §6.3 G4, validates `testrail_section` via
   `testRail.resolveSectionByPath()`, appends the `# Review` section, returns
   `AgentResult` with `test_plan.md` + accumulated token metrics (`drainUsage()` / `accumulate`
   pattern, unchanged).

Retry inputs: `test_plan.md` (optional) → "revise this, do not regenerate from scratch";
`hitl_feedback.md` (optional) → a priority corrections block. Built by `buildPreviousPlanBlock`
and `buildHitlFeedbackBlock`.

`maxTokens()` is `16384`.

**Prompts — router:** `factory-flow-cypress/src/main/resources/prompts/cypress-area-router/`

`system.md` — SKILL.md's **Area Detection** and **step 3** rules verbatim (the "story is the
source of truth" / "trust story over prefix" / "refs = what the case verifies" paragraphs), plus:

> Return exactly this block and nothing else:
> ```
> AREAS: <context-file-stem>[, <second-stem>]
> REFS: <comma-separated Jira keys>
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
> - Also state `team:` and `ecs_enabled:` on their own lines in `# Analysis`.
> - Additional per-case fields not in the skill: `- **Automatable:** Yes/No` and an optional
>   `**Automation notes:**` line referencing real helpers from the fragment library.
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
`team`, `ecs_enabled` and `testrail_section` from the frontmatter, falling back to
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
| `AreaDetectorTest` | `resolve()` maps router names to real files (case/hyphen tolerant); drops unknown names; falls back to keyword scoring on empty/failed router output; story text beats Jira prefix; two-area spanning; truncates >2 |
| `AreaRouterTest` | 3-line block parsed correctly; missing `REFS:` line → issue key alone; garbage output → keyword fallback, run continues; LLM exception → keyword fallback, run continues |
| `RefsAssemblyTest` | issue key always first and always present — when the router omits it, returns unrelated keys only, returns malformed junk (`"the story"`, `"ERM"`, `"123"`), returns duplicates in mixed case, or fails entirely; non-Jira-key values dropped; order stable |
| `TestPlanParserTest` | `# Test Cases` scoping (a `## Notes: x` heading in `# Analysis` must **not** parse as a case); multi-line expected; nested preconditions; validator catches zero cases / zero steps / blank expected |
| `ContextPrefetchTest` | reads from clone; GitHub fallback; classpath fallback; each of the 5 thin-context triggers |
| `SkillWorkspaceServiceTest` | tier resolution clone → GitHub → classpath, and the reported `Source` for each; `isReady()` false before sync; **`requireSkill()` throws when all three tiers fail**; missing context file returns empty (soft) while missing SKILL.md throws (hard); stale-clone-used-when-pull-fails |
| `DevTeamCatalogTest` | all 27 teams parse; name → TestRail id; case-insensitive and `"… Team"`-tolerant lookup; the TestRail spelling resolves where it differs from Jira (`BAMA` → `Bama`); unknown team left untouched rather than guessed; a recognised team with no TestRail id is found but not syncable |

**Unit — worker behaviour**

`TestCaseGenAgentWorkerTest`, driven by `StubChatModel`: fresh run; retry with seeded `test_plan.md` + `hitl_feedback.md`; review pass-first /
pass-second / fail-all-three; feedback accumulation; token accumulation across all calls;
`maxReviewIterations=0`; **plus** malformed-output → format-correction retry → success, and
malformed twice → `AgentExecutionException`.

`TestRailSyncAgentWorkerTest` — metadata mapping and HTML rendering for every TestRail field;
`refs` absent from the frontmatter → `issue_key`; `refs` present but missing `issue_key`
(a Gate 1 amendment) → `issue_key` prepended.

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
| `AREA_KEYWORDS` names drift from MQA's 29 context files | Startup WARN drift check. Affects only the keyword fallback path. |
| Skill clone unavailable | Three-tier chain per asset (Part I §4). `SKILL.md` is a hard dependency and fails the step rather than degrading; everything else proceeds with a note in `# Analysis`. |
| Bundled classpath copies go stale unnoticed | Source + age printed in `# Analysis` every run; startup WARN on >10% size drift; `scripts/update-cypress-testcase-skill-bundle.sh` run deliberately (Part I §4). |
| `# Analysis` is a section rather than its own artifact, so amending it at Gate 1 means editing one textarea | Fewer fields, not more. Revisit if reviewers complain. |
| Two-area stories double the context file budget (up to 26k tokens) | Cap at 2 areas; second only within 60% of top score. |


## 10. Open decisions

1. **Area table source of truth** — the Java `AREA_KEYWORDS` map is authoritative, with a
   warn-only drift check against SKILL.md's table rather than parsing that table at runtime.
   The map feeds both the router prompt and the keyword fallback, so drift is costly.
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
   keyword fallback with a WARN.
4. Step counts near the House Style median for the area (Inventory ~5, Orders ~8, Fees/Fines ~2).
5. No semicolon-joined field lists; toast text quoted verbatim; absolute numeric values.
6. Frontmatter carries all five sync fields (`issue_key`, `refs`, `team`, `ecs_enabled`,
   `testrail_section`); `testrail_section` resolves.
   **`team` must equal the story's Jira Dev Team**, never a value inferred from the subject
   matter. With `FACTORY_DEBUG_ENABLED=true`, the exact value read off
   the ticket is in `debugPrompts/<executionId>/2-generate-attempt1.md` as
   `**Development Team:** <value>`; compare it against the frontmatter and against Jira. There
   must be **no second `---` block** in the body duplicating these fields.
   Watch the log for `is not in references/dev-teams.md` or `has no TestRail id` — either means
   TestRail's Dev Team will be left unset, and both name the team so the table can be corrected.
7. Gate 1 → Approve → cases land in the correct TestRail section with correct metadata.
   Open a created case in TestRail and confirm the **References** field contains the originating
   ticket key (plus any extras the router added).
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
      Most likely the system prompt is not byte-identical between calls: diff
      `debugPrompts/<executionId>/2-generate-attempt1.md` against `4-generate-attempt2.md`
      above the first `## ` user section.
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
| `ecs-user-setup` skill | `.github/skills/ecs-user-setup/SKILL.md` (mirrored in `.claude/skills/`) | Authoritative multi-tenant user setup rules — must be consulted for any `ecs_enabled: true` test |
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
factory-flow-cypress/src/main/resources/prompts/cypress-testcase-reviewer/system.md
factory-flow-cypress/src/main/resources/prompts/cypress-automation-agent/system.md   (Part III)
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

> **For AQA engineers:** Writing `cypress-automation-agent/system.md` is your job — you know
> `CLAUDE.md` and the ECS skill best. Same approach: copy relevant sections, add output format
> instructions, embed a few real `.cy.js` examples.

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
      PromptDump.java                        (rendered-prompt capture, debug only)
      TestCaseGenAgentWorker.java           (LLM: router call, generation, review loop)
      TestRailSyncAgentWorker.java          (non-LLM: push cases, write real ids back)
      CypressAutomationAgentWorker.java     (LLM)                          [Part III]
      CypressExecutionWorker.java           (process runner, no LLM)       [Part III]
      CypressFinalizerWorker.java                                          [Part III]
  src/main/resources/
    flows/cypress-factory.yaml
    cypress-exec.yaml                       (execution env config)         [Part III]
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
    prompts/cypress-testcase-reviewer/
      system.md
      user.md
    prompts/cypress-automation-agent/       [Part III]
      system.md
      user.md
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
| Area context files (29 × `references/context/*.md`) | MQA skill repo | Loaded at runtime for the detected area. |
| TAF fragments and `permissions.js` | `stripes-testing` | Scored and excerpted for automation notes and the `Automatable` flag. |

Part III additionally reuses `ScriptBundleCodec` (`factory-agents`) and TAF's `CLAUDE.md` and
`ecs-user-setup/SKILL.md` as system-prompt sources.

## Environment Variables

| Variable | Purpose |
|---|---|
| TAF path | Auto-managed: `TafSyncService` clones to `{user.dir}/cypressTafClone` on startup — no env var needed |
| `FACTORY_CYPRESS_FOLIO_AI_REPO` | GitHub repo (`owner/name`) containing the FOLIO AI skills. Cloned to `skillsClone/` at startup by `SkillWorkspaceService`; the GitHub API and the jar-bundled copies are the second and third fallbacks. Supplies SKILL.md, `examples.md` and the area context files. |
| `FACTORY_CYPRESS_TARGET_REPO` | GitHub repo (`owner/name`) where generated Cypress specs are committed (e.g. `folio-org/stripes-testing`) |
| `FACTORY_CYPRESS_BASE_BRANCH` | Base branch in `TARGET_REPO` (default: `master`) |
| `FACTORY_CYPRESS_TESTRAIL_SECTION_ID` | Fallback TestRail section ID (long integer); used when `testrail_section` is absent from `test_plan.md` frontmatter, or present but unresolvable against TestRail |
| `FACTORY_CYPRESS_TESTRAIL_CASE_TYPE_ID` | TestRail case type ID (e.g. `1`=Functional on foliotest); code default `1`=Functional — override to change |
| `FACTORY_CYPRESS_TESTRAIL_PRIORITY_ID` | TestRail priority ID (default: `3`=Medium) |
| `FACTORY_CYPRESS_TESTRAIL_LABEL` | Label title applied to every created case (default: `cypressFlow-delete`); resolved to a label ID at runtime via `getOrCreateLabel` |
| `FACTORY_CYPRESS_MAX_REVIEW_ITERATIONS` | Max agent review iterations inside `TestCaseGenAgentWorker` per run (default `3`). Set to `0` to skip automated review entirely. |
| `FACTORY_CONNECTORS_TESTRAIL_BASE_URL` | TestRail instance base URL |
| `FACTORY_CONNECTORS_TESTRAIL_USERNAME` | TestRail username (email) |
| `FACTORY_CONNECTORS_TESTRAIL_API_KEY` | TestRail API key |
| `FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID` | TestRail project ID (integer) |
| `FACTORY_CONNECTORS_TESTRAIL_SUITE_ID` | TestRail suite ID (integer; required on multi-suite projects) |
| `FACTORY_CONNECTORS_GITHUB_TOKEN` | GitHub personal access token (read for context/examples fetch; write for finalizer PR) |
| `FACTORY_CONNECTORS_GITHUB_BASE_URL` | GitHub API base URL (default: `https://api.github.com`; override for GHE) |
| `FACTORY_DEBUG_ENABLED` | Dumps every rendered prompt to `debugPrompts/<executionId>/` and exposes `POST /api/debug/partial-run`. Local use only. |
| `FACTORY_TAF_HAS_ENV_CONFIG` | Set `true` to enable execution mode in `CypressExecutionWorker` (Phase 3+) |
| `FACTORY_CYPRESS_EXEC_CONFIG` | Path to `cypress-exec.yaml`; defaults to bundled `src/main/resources/cypress-exec.yaml` |
| `FACTORY_CYPRESS_REGULAR_ENV` | Deploy-time override for regular-test env params (`key=val,key=val`); merged on top of `cypress-exec.yaml` regular block |
| `FACTORY_CYPRESS_ECS_ENV` | Deploy-time override for ECS-test env params (`key=val,key=val`); merged on top of `cypress-exec.yaml` ecs block |

---


---

# Part III — Cypress automation (not built)

Design only — none of the following is implemented. The input is `test_plan.md` carrying real
TestRail C###### ids, which is what Part I produces.

---

## 1. `CypressAutomationAgentWorker` — LLM worker (yaml id: `cypress-automation-agent`)

**Do not write this from scratch.** The content already exists in the TAF.

**Build the system prompt from these sources in order:**

1. **Core patterns from `CLAUDE.md`** — embed the relevant sections verbatim:
   - Key Development Patterns (test data, fragment architecture, interactors)
   - Test File Creation rules (tagging, naming, step comments)
   - Permission vs Capability Selection Strategy (the CRITICAL DECISION RULE section)
   - Permissions Approach Implementation
   - Capabilities Approach Implementation
   - Common Gotchas

2. **ECS rules from `.github/skills/ecs-user-setup/SKILL.md`** — embed the decision tree and
   code patterns. The factory LLM cannot invoke IDE skills; the guidance must be in the prompt.

3. **Fragment library injection** — the fragment excerpt produced by `FragmentPrefetch`
   is included in the user-facing prompt template (not the system prompt, since it changes per run).

4. **Few-shot examples** — 3-5 real `.cy.js` specs from the TAF (one per complexity level: simple
   CRUD, permission-based, ECS). These go in the system prompt and anchor the LLM to the actual
   code style, not a reconstructed approximation of it.

**Important:** this worker reads `test_plan.md` and **skips any case where `automatable: false`**.
Only automatable cases get a Cypress spec. The prompt must be told this explicitly — instruct the
LLM to ignore non-automatable cases entirely rather than generating a stub or placeholder for them.

**What the prompt must add beyond CLAUDE.md:**

- Output format: `ScriptBundle` (same frontmatter format as Flow A)
- File path derivation: place the spec under `cypress/e2e/` using the test case context from
  `test_plan.md` (title, scope, feature area) and its frontmatter as the folder structure
  guide — same way CLAUDE.md instructs the IDE agents. By this stage, `test_plan.md` already
  carries real `C######` IDs (assigned by `testrail-sync-agent`). The LLM derives folder structure
  from content and context, not from TestRail section hierarchy. The mapping is not 1:1; do not
  prescribe a rigid template — trust LLM judgment, as existing TAF agents already do.
- Real ID usage: by the time this worker runs, `test_plan.md` already carries real `C######` IDs
  (assigned by `testrail-sync-agent` after Gate 1). Use them directly throughout the specs —
  tags `['extendedPath', 'spitfire', 'C627249']`, test data `AT_C627249_EntityType_${getRandomPostfix()}`,
  variable names `c627249EntityName`, file names `c627249-feature.cy.js`. No find-replace needed.
- Import depth rule: derive `../../../support/` depth from the spec file's path in the bundle
- Fragment strategy: the LLM may freely add new methods or variables to existing fragments.
  For gaps with no reasonable existing analogue (entirely new app, entity type, etc.), generate
  a new fragment file and include it in the `ScriptBundle` alongside the specs. The bundle may
  therefore contain both `.cy.js` spec files and `support/fragments/**/*.js` fragment files —
  all committed together in the same PR by the finalizer.
  New fragment files must open with this banner comment:
  ```javascript
  // ⚠️  AI-GENERATED DRAFT — NEEDS HEAVY REVIEW BEFORE USE
  // This fragment was scaffolded automatically. Verify all selectors, method names,
  // and interactions against the actual UI before relying on this in tests.
  ```
- Team tag: read from `test_plan.md` as a string (e.g. `spitfire`). The team name is sourced
  from the Jira story's Dev Team field and written into the `test_plan.md` frontmatter by the
  generation worker. If the value is `unknown`, use
  `'N/A - please fill'` as the tag and add this banner at the top of the spec:
  ```javascript
  // ⚠️  TEAM TAG UNRESOLVED — Jira team value did not match any known team in the mapping table.
  // Replace 'N/A - please fill' in the tags array with the correct team tag before merging.
  ```

---


## 2. `CypressSanityPostProcessor` — post-processor (no YAML entry — auto-runs after every step)

Registered globally — runs after **every** agent step, not just `cypress-automation-agent`.
The guard `if (outputs.get("test_scripts.md") == null) return;` at the top of `process()` means
it silently no-ops for all other steps. Mirrors `KarateSanityPostProcessor`.

Checks (applied only to `.cy.js` files; fragment files are passed through without validation):

Hard failures — throw `AgentExecutionException` (triggers LLM retry):
- Every spec file contains `describe(` and `it(`
- Each `it()` call has a `tags:` array with exactly 3 elements
- No raw `cy.get('` calls (interactors or fragment methods must be used)
- New fragment files (anything under `support/fragments/`) contain the AI-draft banner comment

Warnings — log but don't fail:
- Missing `before()`/`beforeEach()` or `after()`/`afterEach()` (valid for simple read-only specs)
- Spec file has no `import` statements (acceptable for simple/API tests; flag for Gate 2 reviewer)
- Multiple `it()` blocks in one file
- Import depth in path strings does not match the file's actual nesting level (hard to verify statically; flag for Gate 2 reviewer)

---


## 3. `CypressExecutionWorker` — non-LLM worker (yaml id: `cypress-execution-agent`)

**Advisory mode** (default when `FACTORY_TAF_HAS_ENV_CONFIG` is not `true`): produces `test_results.md` with `mode: ADVISORY`, all cases `NOT_EXECUTED`.

**Execution mode**: reads `test_scripts.md` (Cypress bundle with real C###### IDs) and
`test_plan.md` (to know the `ecs_enabled` flag per case, used to split specs into regular vs ECS
buckets). Writes specs and any new fragment stubs to their final paths in the TAF root
(`cypress/e2e/` for specs, `cypress/support/fragments/` for fragments), then runs Cypress.
Both artifacts must be declared in the step's `inputs:` in the YAML.

### Why `environments.js` is not used

`environments.js` is gitignored — the factory cannot rely on it being present or correctly
configured on the execution host. Instead, the worker passes all env params explicitly via
command-line flags, bypassing the TAF's local-dev mechanism entirely.

### Split runs: regular vs ECS

Regular and ECS tests require different environments and env params and cannot run together.
The worker splits the bundle into two buckets using the `ecs_enabled` flag per case in
`test_plan.md`, then runs them sequentially:

```
# Before any run: clear stale allure results so both runs contribute to one report
rm -rf allure-results

# Regular tests (ecsEnabled=false)
npx cypress run -b chrome \
  --spec <regular-spec-paths> \
  --config baseUrl=<regular.baseUrl> \
  -e OKAPI_HOST=...,OKAPI_TENANT=...,diku_login=...,diku_password=...,eureka=true,ecsEnabled=false,allure=true

# ECS tests (ecsEnabled=true)  — only if ECS specs exist
npx cypress run -b chrome \
  --spec <ecs-spec-paths> \
  --config baseUrl=<ecs.baseUrl> \
  -e OKAPI_HOST=...,OKAPI_TENANT=...,ecs_env_name=...,eureka=true,ecsEnabled=true,allure=true

# After all runs: generate the HTML report (both runs' results merged into one)
allure generate allure-results --clean -o allure-report
```

`allure=true` must be passed explicitly because the factory bypasses `environments.js`. This
activates `@shelex/cypress-allure-plugin` which is already wired in the TAF's `cypress.config.js`
(`allureWriter(on, config)` in `setupNodeEvents`) — no TAF changes needed.

Note: `baseUrl` is a top-level Cypress config option → `--config baseUrl=...`, not `-e baseUrl=...`.
Both runs accumulate JSON results under `allure-results/` before `allure generate` runs once at
the end. Skip a bucket entirely if it has no specs.

**Prerequisite:** Allure CLI must be available on the execution host (`allure` on PATH). Install
via `npm install -g allure-commandline` or system package manager.

### Allure report output and serving

The worker generates the HTML report to:
```
{user.dir}/cypressTafClone/allure-report/
```

After generation, the worker records the absolute path in `test_results.md` metadata:
```yaml
allure_report_path: /absolute/path/to/stripes-testing/allure-report
```

**Serving the report** — two options:

1. **Local / developer use:** `allure open allure-report` in the TAF root — opens a browser tab
   directly. Run this manually after the factory execution completes.

2. **Server / CI use:** Add a static-file endpoint to `factory-app` that maps
   `/executions/{id}/allure/**` to the `allure_report_path` stored in `test_results.md`.
   This is a small addition to `factory-app` (one `@GetMapping` with a `Resource` response)
   and lets the factory UI render a "View Allure Report" link on the execution page.
   See Effort Estimate — this is listed as an optional add-on.

### `cypress-exec.yaml` — the execution config file

Lives at `factory-flow-cypress/src/main/resources/cypress-exec.yaml`. Checked in, team-specific.
Each factory deployment owns this file and sets it to match their FOLIO environments:

```yaml
regular:
  baseUrl: "https://folio-etesting-cypress-diku.ci.folio.org"
  env:
    OKAPI_HOST: "https://folio-etesting-cypress-kong.ci.folio.org"
    OKAPI_TENANT: "diku"
    diku_login: "diku_admin"
    diku_password: "admin"
    eureka: true
    ecsEnabled: false

ecs:
  baseUrl: "https://folio-ecs-snapshot.ci.folio.org"
  env:
    OKAPI_HOST: "https://folio-ecs-snapshot-kong.ci.folio.org"
    OKAPI_TENANT: "consortium"
    diku_login: "consortium_admin"
    diku_password: "admin"
    ecs_env_name: "snapshot"
    eureka: true
    ecsEnabled: true
```

### Three-tier precedence (highest wins)

```
1. Gate 2 per-run override   — reviewer types key=val,key=val into the review form
2. Deploy-time env vars      — FACTORY_CYPRESS_REGULAR_ENV / FACTORY_CYPRESS_ECS_ENV (key=val string)
3. cypress-exec.yaml         — checked-in baseline
```

Tier 2 env vars let CI/CD inject real credentials without modifying the committed file.
Tier 1 handles one-off edge cases without touching config or restarting the server.

### Gate 2 execution section

When `FACTORY_TAF_HAS_ENV_CONFIG=true`, Gate 2 renders an **Execution Plan** section beneath
the spec review (invisible in advisory mode). It shows the resolved commands — with all params
filled from the precedence chain — before anything runs:

```
── Execution Plan ──────────────────────────────────────────────────────
Regular tests (C627249, C627250): 2 specs
  baseUrl : https://folio-etesting-cypress-diku.ci.folio.org
  env     : OKAPI_HOST=..., OKAPI_TENANT=diku, ecsEnabled=false, eureka=true, ...
  override: [_________________________________]  ← key=val,key=val or leave blank

ECS tests (C627251): 1 spec
  baseUrl : https://folio-ecs-snapshot.ci.folio.org
  env     : OKAPI_HOST=..., ecs_env_name=snapshot, ecsEnabled=true, eureka=true, ...
  override: [_________________________________]  ← key=val,key=val or leave blank
────────────────────────────────────────────────────────────────────────
```

Note: IDs shown here are real `C######` IDs — assigned by `TestRailSyncAgentWorker` after
Gate 1 approval. By the time Gate 2 is reached, all IDs are already final TestRail case IDs.

Override fields use the same `key=val,key=val` format Cypress accepts natively.
Override values are merged on top of the resolved config (not a full replacement).
Overrides are stored in the HITL decision payload and applied only to this run.

Parse `cypress/results/*.xml` (JUnit output). Timeout: 30 minutes minimum per run.

---


## 4. `CypressFinalizerWorker` — non-LLM worker (yaml id: `cypress-finalizer`)

Adapted from `TestFactoryFinalizerWorker`. Case creation is already done by
`TestRailSyncAgentWorker`, so this worker handles the remaining GitHub and reporting work:

- **Commit all files** from `test_scripts.md` bundle to `stripes-testing` — both `.cy.js`
  specs and any new fragment stubs under `cypress/support/fragments/`
  - Branch: `test-factory/cypress-{issueKey}`
  - PR title: `{issueKey}: AI-generated Cypress E2E specs`
  - PR body: list of covered TestRail case IDs; note any AI-draft fragment files included
- **Post a Jira comment** summarizing what was done — total cases pushed to TestRail, how
  many are automated, PR link; if execution mode was used, append run summary: passed / failed /
  skipped counts and names of failed specs (read from `test_results.md`)

---


## Phase 2 — Cypress spec generation + Gate 2

**Goal: Gate 2 produces a reviewable `.cy.js` bundle with real C###### IDs (already in place).**

9. Build `cypress-automation-agent/system.md`:
   - Start with CLAUDE.md relevant sections (copy verbatim, don't paraphrase)
   - Add ECS rules from `ecs-user-setup/SKILL.md`
   - Add output format instructions (ScriptBundle, real ID usage, path rules, tag format)
   - Find 3-5 real `.cy.js` examples from TAF spanning simple/permission/ECS cases — embed as
     few-shot examples in the prompt
10. Implement `CypressAutomationAgentWorker` (mirrors `TestAutomationAgentWorker`)
11. Implement `CypressSanityPostProcessor`
12. Extend `cypress-factory.yaml` to include `cypress-automation` + Gate 2
13. End-to-end test: run 3-5 real Jira stories, compare output quality at Gate 2

**Milestone:** Approved bundle at Gate 2 has correct structure with real `C######` IDs throughout.

**Verify:** Inspect the Gate 2 review panel — `test_scripts.md` should contain `.cy.js` files
with `tags: ['extendedPath', 'spitfire', 'C627249']` style tags, `AT_C627249_` prefixed variable
names, and no raw `cy.get('` calls (sanity post-processor should have caught those already).

**Iteration expected:** Plan for 3-5 prompt tuning rounds on `cypress-automation-agent`, the
same way test case generation needed them. For case generation, the context files + SKILL.md
writing
guidelines are the highest-leverage knob — they provide FOLIO-specific factual grounding
(exact UI texts, business rules, capability sets) that the LLM cannot reliably produce from
training data alone. Few-shot examples are the highest-leverage knob for `cypress-automation-agent`.

---

## Phase 3 — Execution and finalize

**Goal: Specs run (or skip), PR opens in stripes-testing, Jira gets a comment.**

14. Implement `CypressExecutionWorker` (advisory mode first, real execution second)
15. Implement `CypressFinalizerWorker`
16. Full end-to-end test

**Milestone:** Full pipeline from Jira story to stripes-testing PR with real `C######` IDs and
TestRail cases created only after human approval.

**Verify:** Check TestRail — new cases appear in the configured section with the AI label (67).
Check the PR in stripes-testing — spec files use `C######` tags and `AT_C######_` variable names
(not `TC###`). If execution mode was used: `allure-report/` directory exists in the TAF root
and the Allure HTML opens correctly.

---


## Risks — Cypress automation

Part I's risks are in Part I §9. These are specific to the phases below.

### Fragment accuracy (highest LLM quality risk for spec generation)
`FragmentPrefetch` mitigates this significantly. Even with the index, the LLM may use
wrong method names. The Gate 2 HITL review and the resulting PR review in stripes-testing are
the safety nets — not the automated checks.

### Area detection accuracy (spec generation)
The keyword-matching table from the skill handles most stories. Ambiguous stories (touching multiple
modules) may detect the wrong area. Mitigations:
- Show `detected_area` and `confidence` in Gate 1 review — the QA reviewer can catch and
  note corrections before approving
- Log detection results clearly so misdetections are easy to diagnose

### Permissions / Capabilities dictionary (500+ entries)
The context files already provide the relevant capability sets and legacy permissions per module.
The LLM uses this rather than guessing or scanning the full dictionary.

### ECS test complexity
The `ecs-user-setup` skill content is detailed and correct. Embedding it verbatim in the system
prompt is the right approach. In v1, consider flagging ECS tests at Gate 2 with a special warning
note to the reviewer: "This test was classified as ECS — review user setup carefully."

### TestRail partial failure — TC-X leaking to Phase 2 (Phase 2 prerequisite)

If `addCaseWithMetadata` succeeds for some cases but throws for others, the failed cases keep
their `TC-X` placeholder in `test_plan.md`. `cypress-automation-agent` reads those IDs and
embeds them verbatim in spec tags (`['TC-1']`), variable names (`AT_TC-1_...`), and filenames.
The TestRail link for those specs is silently broken.

Retrying the step (via the bounded retry policy) would re-create the successful cases again —
duplicate TestRail entries.

**Mitigation before Phase 2:** distinguish failure types in `TestRailSyncAgentWorker`:
- All cases fail → almost certainly credentials/config → throw immediately (no duplicates)
- Some cases fail → write a `testrail_failures` list into `test_plan.md` frontmatter so
  `cypress-automation-agent` can skip or flag those cases rather than treating `TC-X` as real.

### Test case quality feeding spec generation
The `write-testrail-cases/SKILL.md` writing guidelines and area context files are a significant
upgrade from the generic Flow A prompts. The context files provide verbatim UI texts, verified
business rules, and exact capability set names — the factual grounding that previously had to
come from the LLM's training data (unreliable for specific FOLIO versions).

Remaining mitigations:
- `automatable: true/false` flag per case — Gate 1 is the main quality control; QA confirms or overrides
- Non-automatable cases still go to TestRail; just skipped by the automation agent
- The `test_scripts.md` bundle will have fewer specs than test cases in `test_plan.md`; this is expected (non-automatable cases are skipped) and noted in `sync_report.md`

## Effort Estimate

Part I is built, so only the remaining phases are estimated here.

| What | Estimate |
|---|---|
| `cypress-automation-agent/system.md` (initial + 3-5 tuning rounds) | 2-3 days |
| `CypressAutomationAgentWorker` + `CypressSanityPostProcessor` | 1 day |
| `CypressExecutionWorker` (advisory mode) | 0.5 day |
| `CypressExecutionWorker` (real execution) | 1 day |
| `CypressFinalizerWorker` | 1 day |
| Gate 2 + `cypress-factory.yaml` extension | 0.5 day |
| Phase 2-3 end-to-end testing | 2 days |
| Allure report serving endpoint in `factory-app` (optional) | 0.5 day |

**Total: ~8-9 days for Phases 2-3** (+ 0.5 day optional Allure serving)

Starting from `CLAUDE.md` (TAF's tested instructions) for `cypress-automation-agent` saves
meaningful prompt engineering time, the same way starting from MQA's `SKILL.md` did for Part I.

---


## Implementation Reference — How to Actually Write the Workers

> This section is for engineers new to the factory codebase. It shows the exact patterns to copy
> for each new component. Read the existing Flow A code alongside these examples —
> `factory-flow-test-factory` is your reference implementation.

### `@Component` vs `@Bean` — when to use which

You will notice that some workers have `@Component` on the class and others don't. The rule:

- **Post-processors** (`implements StepPostProcessor`) → always `@Component` on the class.
  The engine discovers them via Spring's component scan — no manual wiring needed.
- **Non-LLM workers** (`implements AgentWorker`) → either `@Component` on the class **or**
  a `@Bean` method in `CypressFlowConfiguration`. Both work. Use whichever matches the nearest
  Flow A analog.
- **LLM workers** (`extends AbstractLlmAgentWorker`) → typically a `@Bean` in the config class
  because they need a `ChatClient` injected at construction time, and the config class is where
  `ChatClient` is built.

When in doubt: open `FlowAConfiguration` in `factory-flow-test-factory` and find the analogous
worker — copy exactly how it's registered there.

---

### The three-sentence mental model

Every worker receives an `AgentContext` (inputs + config), does something, and returns an
`AgentResult` (outputs). Non-LLM workers do it in pure Java. LLM workers extend
`AbstractLlmAgentWorker` and call `callForEntity(vars, MyRecord.class)`. That's it.
The engine handles orchestration, retries, artifact storage, and HITL pauses — your worker
never touches any of that.

---


### LLM worker: `CypressAutomationAgentWorker`

Copy the shape of `TestAutomationAgentWorker`. Extend `AbstractLlmAgentWorker`. You get
`callForEntity()` and prompt loading for free.

```java
// factory-flow-cypress/src/main/java/.../worker/CypressAutomationAgentWorker.java

public class CypressAutomationAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "cypress-automation-agent";

    private final ScriptBundleCodec bundleCodec;

    public CypressAutomationAgentWorker(ChatClient chatClient,
                                        ScriptBundleCodec bundleCodec) {
        super(chatClient);    // ← always pass ChatClient to super
        this.bundleCodec = bundleCodec;
    }

    @Override
    public String id() { return ID; }

    @Override
    public AgentResult execute(AgentContext context) throws AgentExecutionException {

        // 1. Read inputs
        String testPlan       = context.requireInput("test_plan.md").content();
        String fragmentLibrary = fragmentPrefetch.scan(areas, summary);

        // 2. Call the LLM — vars map becomes {{placeholder}} substitutions in user.md
        //    callForEntity parses the LLM's JSON response into a ScriptBundle record
        ScriptBundle bundle = callForEntity(
            Map.of(
                "test_plan",       testPlan,
                "fragments", fragmentLibrary
            ),
            ScriptBundle.class   // ← the record the LLM must produce; defined in your model package
        );

        // 3. Validate and return
        if (bundle.files().isEmpty()) {
            throw new AgentExecutionException("LLM returned an empty script bundle");
        }
        return resultWithUsage("test_scripts.md", bundleCodec.render(bundle));
    }
}
```

The prompt files live at:
```
src/main/resources/prompts/cypress-automation-agent/system.md   ← static instructions
src/main/resources/prompts/cypress-automation-agent/user.md     ← template with {{test_plan}}, {{fragments}}
```

The base class loads them by `workerId` automatically — the file names are the contract.
`{{placeholder}}` in the `.md` file is replaced with whatever key you put in the `Map.of(...)`.

---


### Post-processor: `CypressSanityPostProcessor`

Copy `KarateSanityPostProcessor`. Annotate with `@Component` — the engine auto-discovers all
`StepPostProcessor` beans and runs them after **every** agent step.

```java
// factory-flow-cypress/src/main/java/.../quality/CypressSanityPostProcessor.java

@Component   // ← this is how the engine finds it; no manual wiring needed
public class CypressSanityPostProcessor implements StepPostProcessor {

    @Override
    public void process(FlowDescriptor flow, StepDescriptor step,
                        Map<String, String> outputs) throws AgentExecutionException {

        // Guard: this processor only cares about cypress spec output
        // If the current step didn't produce test_scripts.md, do nothing
        String raw = outputs.get("test_scripts.md");
        if (raw == null) return;

        // Parse the bundle — ScriptBundleCodec splits the multi-file Markdown artifact
        // into individual { path, content } entries
        ScriptBundle bundle = bundleCodec.parse(raw);

        for (ScriptBundle.ScriptFile file : bundle.files()) {
            if (!file.path().endsWith(".cy.js")) continue;   // skip fragment stubs
            String src = file.content();

            // Hard failures — throwing here triggers the step retry budget
            if (!src.contains("describe(") || !src.contains("it(")) {
                throw new AgentExecutionException(
                    "Spec " + file.path() + " is missing describe() or it()");
            }
            // ... other hard checks from section 7 of this plan
        }
        // Warnings: log but return normally — step succeeds
    }
}
```

Key rule: **throw `AgentExecutionException` for hard failures; log for warnings.** A thrown
exception burns one retry from the step's `retry_policy`. Warnings are free.

---


### Non-LLM worker with external connector: `TestRailSyncAgentWorker`

Same shape as any non-LLM worker, but with `TestRailConnector` injected.

```java
// factory-flow-cypress/src/main/java/.../worker/TestRailSyncAgentWorker.java

public class TestRailSyncAgentWorker implements AgentWorker {

    public static final String ID = "cypress-testrail-sync";

    private final TestRailConnector testRail;
    private final FrontmatterCodec frontmatterCodec;

    // constructor injection (omitted for brevity)

    @Override
    public String id() { return ID; }

    @Override
    public AgentResult execute(AgentContext context) throws AgentExecutionException {

        // 1. Read test_plan.md (Gate-1-approved cases, no IDs yet)
        String testPlanRaw = context.requireInput("test_plan.md").content();

        // 2. Parse cases (all cases — automatable + non-automatable)
        List<TestCase> cases = parseCases(testPlanRaw);

        // 3. Push each case to TestRail, collect title→realId mapping
        Map<String, String> idMap = new LinkedHashMap<>();
        for (TestCase tc : cases) {
            long realId = testRail.addCase(sectionId, tc.title(), renderSteps(tc), renderExpected(tc));   // see TestFactoryFinalizerWorker.syncTestRail() for field mapping
            idMap.put(tc.title(), String.valueOf(realId));              // "Create order" → "627249"
        }

        // 4. Write real C###### IDs back into test_plan.md (each case gets id: C627249)
        String updatedPlan = writeIdsBackToPlan(testPlanRaw, idMap);

        return AgentResult.of("test_plan.md", updatedPlan);
    }
}
```

Look at `TestFactoryFinalizerWorker.syncTestRail()` for the exact `TestRailConnector` API calls.
The `getSection(sectionId)` method needs to be added to `TestRailConnector` (one `GET` call).

---


### Non-LLM worker with GitHub + Jira connectors: `CypressFinalizerWorker`

Copy the shape of `TestFactoryFinalizerWorker`. The key difference: case creation already
happened in `TestRailSyncAgentWorker`, so this worker only handles GitHub commit + PR + Jira.

```java
// factory-flow-cypress/src/main/java/.../worker/CypressFinalizerWorker.java

public class CypressFinalizerWorker implements AgentWorker {

    public static final String ID = "cypress-finalizer";

    private final GitHubConnector github;
    private final JiraConnector jira;
    private final ScriptBundleCodec bundleCodec;

    // constructor injection (omitted for brevity)

    @Override
    public String id() { return ID; }

    @Override
    public AgentResult execute(AgentContext context) throws AgentExecutionException {

        ScriptBundle bundle        = bundleCodec.parse(context.requireInput("test_scripts.md").content());
        String testResultsRaw      = context.requireInput("test_results.md").content();
        String testPlanRaw         = context.requireInput("test_plan.md").content();

        // 1. Commit all files (specs + any fragment stubs) to a new branch in stripes-testing
        String issueKey = parseIssueKey(testPlanRaw);        // from the test_plan.md frontmatter
        String branch   = "test-factory/cypress-" + issueKey;
        String repo     = properties.targetRepo();   // FACTORY_CONNECTORS_GITHUB_* target
        github.createBranch(repo, properties.baseBranch(), branch);
        github.commitFiles(repo, branch, toFileMap(bundle.files()),
            issueKey + ": AI-generated Cypress E2E specs");

        // 2. Open a PR
        String prUrl = github.createPullRequest(
            repo, branch, properties.baseBranch(),
            issueKey + ": AI-generated Cypress E2E specs",
            buildPrBody(bundle, testResultsRaw)
        );

        // 3. Post a Jira comment summarizing results
        jira.addComment(issueKey, buildJiraComment(bundle, prUrl, testResultsRaw));

        // 4. Produce final artifact
        return AgentResult.of("sync_report.md", buildSyncReport(bundle, prUrl, testResultsRaw));
    }
}
```

`buildPrBody` lists all covered TestRail case IDs (`C######`) and flags any AI-draft fragment
files with a note for reviewers. `buildJiraComment` includes PR link + run summary (if execution
mode was used; advisory mode → omit run summary).

---


### Subprocess runner pattern (`CypressExecutionWorker`)

For running `npx cypress run`, use `ProcessBuilder` — the same approach `TestExecutionWorker`
uses for `java -jar karate.jar`:

```java
ProcessBuilder pb = new ProcessBuilder(
    "npx", "cypress", "run",
    "-b", "chrome",
    "--spec", String.join(",", specPaths),
    "--config", "baseUrl=" + config.baseUrl(),
    "-e", buildEnvString(config.env())   // "OKAPI_HOST=...,OKAPI_TENANT=..."
);
pb.directory(tafSyncService.tafRoot().toFile());
pb.redirectOutput(outputFile);   // write stdout+stderr to a temp file, not a pipe
pb.redirectErrorStream(true);    // merge stderr into stdout
Process process = pb.start();
boolean finished = process.waitFor(30, TimeUnit.MINUTES);
```

Do **not** use `pb.inheritIO()` or read from `process.getInputStream()` directly — the JVM
pipe buffer will fill and deadlock. Always redirect to a file (`pb.redirectOutput(file)`).
Parse results from the JUnit XML files Cypress writes to `cypress/results/*.xml`.

---

> **Where to look for more:** Every pattern above has a fully working reference in
> `factory-flow-test-factory`. When in doubt, find the nearest analog there and copy its shape.

---


## Suggested Next Steps (Post Phase 3)

> These are extensions to consider after the Phase 1-3 flow is working and tested end-to-end.
> Do not build any of this until the base pipeline is stable.

#### Phase 4 — Automatic fix loop for Cypress failures

Generated specs will have failures on first run — wrong fragment method names, wrong assertion
text, missing imports, timing issues. Phase 3 leaves these as "human fix required." A fix loop
would let the factory self-repair the obvious ones before handing off.

##### How it would work

After `CypressExecutionWorker` produces `test_results.md`, extend the pipeline with:

```
[cypress-execution-agent]   (existing)
    │ test_results.md
    ▼
[failure-analyzer]          ← NEW, non-LLM: parse Allure JSONs, categorize failures
    │ failure_report.md       per failing test: error text, failed step, test intent
    ▼                         each failure tagged FIXABLE / ENV_ISSUE / UNCLEAR
[spec-fix-agent]            ← NEW, LLM: generate targeted fixes for FIXABLE failures only
    │ test_scripts_fixed.md   ScriptBundle with only the modified files — not a full rewrite
    ▼
[sanity-check]              ← existing CypressSanityPostProcessor runs automatically
    ▼
[cypress-execution-agent]   ← re-run failing specs only (--spec <failing-only>)
    │ test_results_vN.md
    ▼
[repeat up to N=2 times]
    ▼
[cypress-finalizer]         ← commit best-passing version; unfixable failures noted in sync_report
```

Enable the loop via env flag so teams can skip it initially:
```
FACTORY_CYPRESS_ENABLE_FIX_LOOP=true   # default: false
FACTORY_CYPRESS_FIX_LOOP_MAX=2         # default: 2
```

##### `FailureAnalyzerWorker` — non-LLM, the critical gating step

Reads Allure JSON files from `allure-results/` and `test_plan.md`. For each failing test,
produces a `failure_report.md` entry with:
- Which step failed and the raw error/assertion message
- The test's original intent (from `test_plan.md`)
- A category tag:
  - `FIXABLE` — structural issues an LLM can reliably fix: import errors, unknown method names
    (cross-referenced against the fragment library), assertion text mismatches where the actual
    value is visible in the error, wrong path depth in imports
  - `ENV_ISSUE` — credential failures, network timeouts, missing test data, tenant not found —
    no code change can fix these; skip them
  - `UNCLEAR` — everything else; pass to the fix agent but with low confidence

This step prevents the LLM from burning iterations on environment problems that will never be
fixed by editing code.

##### `SpecFixAgentWorker` — LLM, surgical fixes only

Key constraints the prompt must enforce explicitly:
- Only modify files listed in `failure_report.md` as `FIXABLE` or `UNCLEAR` — do not touch
  passing tests
- Output a ScriptBundle containing **only the modified files** — a diff, not a full rewrite
- Fixing a failure by removing or weakening an assertion is not a fix — flag it as `UNFIXABLE`
  instead
- If a fix requires a new fragment method that does not exist in the fragment library, generate
  a fragment stub and include it in the bundle (same pattern as the automation agent)

Re-run command targets only failing specs:
```bash
npx cypress run -b chrome --spec <previously-failing-spec-paths> ...
```

##### What to report

After N iterations, any still-failing tests are surfaced in `sync_report.md` as
`unfixed_failures` — a list of case IDs with their final error and category. These appear
prominently in the Jira comment so engineers know exactly what needs manual attention.

##### What's realistically fixable

| Failure type | Auto-fixable? |
|---|---|
| Missing import, wrong path depth | Yes |
| Fragment method name mismatch | Yes — fragment index provides correct names |
| Assertion text mismatch (actual value in error) | Yes |
| Wrong capability set name | Yes — context file has canonical names |
| Timing / element not found (simple) | Partially — add wait/retry, but don't over-pad |
| Wrong navigation or business logic | Risky — LLM may weaken the assertion; treat as UNCLEAR |
| Environment: wrong creds, missing data, network | Never — mark ENV_ISSUE and skip |
| ECS multi-tenant setup errors | Probably not — too subtle; treat as UNCLEAR |

##### Prevention is cheaper than repair

Before building Phase 4, consider expanding `CypressSanityPostProcessor` to catch more
structural issues *before* execution: unknown fragment references (cross-check against
the fragment library), wrong import depth (verify against bundle file path), tag format
violations. Better prevention means fewer failures reach the fix loop in the first place.


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
