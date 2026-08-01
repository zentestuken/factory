# Cypress Factory Flow — Implementation Plan

**Story → Manual Test Cases → Cypress E2E specs, using TAF AI assets and MQA skill assets**

---

## What This Does — Happy Path in 5 Steps

Before reading anything else: here is what the factory does from an end-user perspective.

1. **You trigger the flow** via a REST call (`POST /api/triggers/manual` with the Jira issue key) or automatically via a Jira webhook on a story status transition.
2. The factory detects the FOLIO area (e.g. Orders), loads the Orders context file, scans
   the fragment library, and loads golden test case examples — all automatically.
3. **Gate 0 lands**: a categorized list of proposed scenarios (S-01, S-02…) with shape notes
   (journey vs atomic, step count, tier). You confirm coverage, add or remove scenarios, approve.
4. **Gate 1 lands**: the generated test cases (one per confirmed scenario), each with an
   `automatable: true/false` flag. You review, override any flags, and approve.
5. **Gate 2 lands**: the generated `.cy.js` spec files, already tagged with real TestRail
   C###### IDs. You review the code quality and approve.
6. After approval: specs are committed to stripes-testing in a PR, and (if execution mode is
   enabled) Cypress runs and produces an Allure HTML report.

That's it. The rest of this document explains how to build the system that does this.

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

**Context files are runtime-loaded, not embedded:**
The `write-testrail-cases/references/context/*.md` files are fetched from GitHub at execution time
by `ContextLoaderWorker` (single-file GET via GitHub API — no local clone needed). When MQA updates
a context file (e.g., adds new business rules to `inventory.md`), the factory picks up the change
automatically on the next run — no prompt re-sync needed. Only the writing instructions embedded in
`SKILL.md` itself require manual re-sync if they change significantly.

**Ongoing maintenance consequence:** When the TAF's `CLAUDE.md`, `ecs-user-setup/SKILL.md`, or
the MQA `write-testrail-cases/SKILL.md` writing guidelines are updated, the corresponding
factory system prompts must be manually re-synced. Context files (`references/context/*.md`) are
exempt — they auto-sync via runtime load. Recommend a short checklist mapping which source file
maps to which factory prompt section, reviewed whenever a source asset changes.

---

## How TAF / MQA Assets Map to Factory Workers

```
TAF / MQA (interactive, IDE)           Factory (automated, Java + LLM API)
─────────────────────────────────────  ────────────────────────────────────────────
                                       triage-agent         ← scope + context
                                       context-loader       ← detect FOLIO area → load context file
write-testrail-cases/SKILL.md          e2e-spec-agent   ← test case list (no IDs yet; assigned after Gate 1)
context files (runtime-loaded)         injected via ContextLoaderWorker into e2e-spec-agent user prompt
                                       HITL Gate 1          ← human review of test cases
test-implementer.agent.md              cypress-automation-agent ← generate specs (real C###### IDs from test_plan.md)
test-reviewer.agent.md                 CypressSanityPostProcessor + HITL Gate 2
                                       testrail-sync        ← push ALL cases → real C###### IDs
                                                              update test_plan.md with real IDs (after Gate 1)
                                       cypress-execution    ← run with real IDs in place (or skip)
                                       cypress-finalizer    ← commit + update
```

The TestRail MCP server (IDE) and the factory's TestRail Java connector serve the same purpose
from different contexts. The factory uses the Java connector; the MCP server stays in the IDE.

---

## Flow Architecture

```
[Trigger: Jira story (manual trigger or Jira webhook)]
     │
     ▼
[triage-agent]                                               REUSE from Flow A
     │  ── Fetch story + linked issues from Jira API
     │  ── Extract acceptance criteria verbatim → acceptance_criteria[] in scope_manifest
     │  ── Identify affected FOLIO modules; map to test scope boundaries
     │  ── Map "Team" / "Squad" / "Dev team" Jira field → devTeam
     │  ──   Known teams: Firebird, Folijet, Spitfire, Thunderjet, Vega, Volaris,
     │  ──                Citation, Corsair, Eureka, Promin (stored verbatim)
     │  ── Detect ECS context from labels/description → ecs_enabled flag
     ▼
[scope_manifest.md]
     │
     ▼
[context-loader]                                                  NEW · non-LLM
     │  ── Keyword-match story summary + components to one of 19 FOLIO areas
     │  ── Fetch context/{area}.md from FACTORY_MQA_SKILL_REPO via GitHub API
     │       (branch: FACTORY_MQA_SKILL_BRANCH · always reflects latest MQA edits)
     │  ── Provides: verbatim UI texts, business rules, capability sets/permissions for the area
     │  ── On ambiguity: record all matches; reviewer corrects inline at Gate 1
     │  ── On failure / unknown area: context.md with area: unknown; flow continues with empty context.md
     │  ── Thin-context check (all 5 triggers): < 10 KBRs, DRAFT marker, missing "## Exact UI Texts",
     │       < 3 toasts in UI Texts, > 5 known gaps → trigger lightweight enrichment:
     │       (a) GitHub: package.json permissions + keyword-filtered en_US.json translations
     │       (b) Jira: linked issue summaries and descriptions
     │       Prepends context_quality: thin to frontmatter + enrichment note in body
     │       Degrades gracefully — enrichment skipped silently when connectors unset
     ▼
[context.md]
     │
     ▼
[fragment-scanner]                                                NEW · non-LLM
     │  ── TAF auto-cloned to {user.dir}/cypressTafClone at startup (TafSyncService)
     │  ── Reads areas from context.md frontmatter; keywords from scope_manifest.md summary
     │  ── Local path: score by filename (+3/kw) + content (+1/kw); fetch top-2 per area
     │  ── GitHub API fallback when clone not ready (filename scoring only)
     │  ── Extracts matching gui permissions from permissions.js per area
     ▼
[fragment_library.md]
     │
     ▼
[examples-loader]                                                 NEW · non-LLM
     │  ── Fetches examples.md from folio-eureka-ai-dev GitHub repo at runtime
     │       (branch: master, path: skills/write-testrail-cases/references/examples.md)
     │  ── Falls back to classpath:references/examples.md when GitHub unavailable
     │  ── 3 golden TestRail cases with style annotations (terse expected results,
     │       no named actors, compact preconditions, symbolic values, journey flag rules)
     ▼
[examples.md]
     │
     ▼
[scenario-analysis]                                                   NEW · LLM
     │  ── Reads scope_manifest.md + context.md
     │  ── Fetches available TestRail section paths from TestRail API (depth ≤ 2)
     │  ── Maps acceptance criteria → scenario candidates
     │  ── Looks up area House Style (Type, step count, journey/atomic shape)
     │  ── Produces categorized scenario proposals: happy path, business-rule
     │       verification, capability boundaries, negative/edge, ECS variants
     │  ── Each scenario: S-NN | title | Type | Steps: ~N | Journey: Yes/No | Tier
     │  ── Suggests best-matching TestRail section → testrail_section in frontmatter
     │       (path string like "Inventory › Search", or best guess when TestRail unconfigured)
     ▼
[scenario_list.md]  — frontmatter: testrail_section; body: S-NN scenario proposals
     │
     ▼
🛑 HITL Gate 0: Scenario Coverage Review
     │  ── QA confirms WHAT will be tested before any case is written
     │  ── Add missing coverage, remove out-of-scope items
     │  ── Adjust journey vs atomic shape per scenario
     │  ── Edit testrail_section in scenario_list.md frontmatter if suggestion is wrong
     │       (path string with › separators, or numeric ID; delete to use env default)
     │  ── test-spec generates exactly the confirmed scenarios
     ▼
[e2e-spec-agent]                                                      NEW · LLM
     │  ── Reads scope_manifest.md + context.md + fragment_library.md
     │        + examples.md + scenario_list.md
     │  ── System prompt: write-testrail-cases/SKILL.md guidelines (embedded)
     │       + House Style by Area table + Journey vs Atomic guidance
     │       + Step Patterns + Entry Point Exhaustion rule
     │  ── Generates exactly the cases listed in scenario_list.md
     │  ── automatable: true/false per case (no IDs yet — TestRail assigns them)
     │  ── Non-automatable: multi-tab flows, external auth, OS-level ops
     ▼
[test_plan.md]  — lightweight frontmatter (issue_key, team, ecs_enabled, case_count) + body with one ## TC-X: Title section per case; no TestRail IDs yet
     │
     ▼
🛑 HITL Gate 1: Test Case Review
     │  ── QA reviews all cases against story acceptance criteria
     │  ── Override automatable flags; amend steps; reject cases
     │  ── Correct detected_area in context.md inline if wrong
     │  ── scenario_list.md is a reviewed artifact — amend testrail_section here
     │       to override the Gate-0 section before sync (path string or numeric ID)
     │  ── automatable flags are FINAL after approval
     ▼
[testrail-sync-agent]                                             NEW · non-LLM
     │  ── Push ALL cases from test_plan.md to TestRail API — automatable +
     │       non-automatable alike; collect real C###### IDs for each
     │  ── Metadata: automation_type=Manual, labels=[AI label], team, ecs_enabled, refs=issueKey
     │  ── Output updated test_plan.md with real C###### IDs per case
     ▼
[test_plan.md]  — same format; TC-X tokens in body headings replaced with real C###### IDs
     │
     ▼
[cypress-automation-agent]                                            NEW · LLM
     │  ── Reads updated test_plan.md (real C###### IDs) + fragment_library.md
     │  ── Skip automatable: false cases entirely — no spec generated for them
     │  ── System prompt: cypressTafClone/CLAUDE.md + each
     │       cypressTafClone/.github/skills/<skill_name>/SKILL.md (embedded)
     │  ── Generate .cy.js specs; use existing fragments; stub new ones for gaps
     │  ── Real IDs throughout: tags ['C627249'], AT_C627249_ vars, file names
     │  ── New fragment stubs: ⚠️ AI-GENERATED DRAFT banner required
     │  ── ECS cases (ecs_enabled: true): apply multi-tenant user setup rules
     ▼
[test_scripts.md]  — .cy.js specs + fragment stubs, all with real C###### IDs
     │                 CypressSanityPostProcessor runs automatically after this step
     ▼
🛑 HITL Gate 2: Spec Review
     │  ── AQA reviews .cy.js specs and new fragment stubs
     │  ── Sanity checks already passed: describe/it structure, tag format,
     │       no raw cy.get() calls, AI-draft banner on new fragments
     │  ── If FACTORY_TAF_HAS_ENV_CONFIG=true: Execution Plan shown with resolved
     │       npx cypress run commands; reviewer may override env params per bucket
     ▼
[cypress-execution-agent]                                         NEW · non-LLM
     │  ── Advisory mode (default): test_results.md mode: ADVISORY — no run
     │  ── Execution mode: materialise specs + fragments to cypressTafClone/
     │       rm -rf cypressTafClone/allure-results
     │       npx cypress run  (regular specs · ecsEnabled=false · allure=true)
     │       npx cypress run  (ECS specs only if present · ecsEnabled=true)
     │       allure generate allure-results -o cypressTafClone/allure-report
     │  ── Env config: cypress-exec.yaml → FACTORY_CYPRESS_*_ENV → Gate 2 override
     ▼
[test_results.md]  — pass/fail per case, totals
     │
     ▼
[cypress-finalizer]                                          ADAPTED from Flow A
     │  ── Commit test_scripts.md bundle to stripes-testing GitHub repo:
     │       .cy.js specs → cypress/e2e/
     │       fragment stubs → cypress/support/fragments/
     │       branch: test-factory/cypress-{issueKey}
     │  ── Open PR: title "{issueKey}: AI-generated Cypress E2E specs";
     │       body lists C###### IDs; flags AI-draft fragment files
     │  ── Post Jira comment: case count, automated count, PR link, run summary (passed/failed/skipped) if execution mode was used
     ▼
[sync_report.md]
     │
     ▼
[Flow Output: test_plan.md + test_scripts.md + sync_report.md]
     │  ── TestRail: all cases created with real C###### IDs
     │  ── stripes-testing: PR open with .cy.js specs + any new fragment stubs
     │  ── Jira: story commented with case count, PR link, run summary (passed/failed/skipped; execution mode only)
     │  ── Allure HTML report at cypressTafClone/allure-report/ (local)(execution mode only)
```

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
factory-flow-cypress/src/main/resources/prompts/e2e-spec-agent/system.md
factory-flow-cypress/src/main/resources/prompts/cypress-automation-agent/system.md
```

**A developer writes these files during implementation** by reading the source assets (MQA's
`write-testrail-cases/SKILL.md`, the TAF's `CLAUDE.md`, `ecs-user-setup/SKILL.md`) and copying
the relevant sections verbatim into the factory prompt files. This is the extraction work
described in sections 4 and 5 below.

Once written, the factory just reads these files off disk on every run — no code involved,
no dynamic assembly. If a source asset changes significantly, a developer re-syncs the
corresponding `system.md` by hand.

> **For MQA engineers:** Writing `e2e-spec-agent/system.md` is your job — you know the
> `write-testrail-cases` skill best. It is literally copy-paste from SKILL.md plus a few lines
> specifying output format. No Java required.

> **For AQA engineers:** Writing `cypress-automation-agent/system.md` is your job — you know
> `CLAUDE.md` and the ECS skill best. Same approach: copy relevant sections, add output format
> instructions, embed a few real `.cy.js` examples.

### Who assembles the user prompts — the factory, every run

The user prompt templates (`user.md` files) contain placeholders like `{{scope_manifest}}`,
`{{context}}`, `{{fragment_library}}`, `{{test_plan}}`. The factory fills these in at runtime
using the artifacts produced by previous steps in the same execution. A developer writes the
template once; the factory does the assembly on every run.

### What is runtime-loaded vs embedded

| What | How it reaches the LLM | Who keeps it current |
|---|---|---|
| `system.md` files | Embedded at implementation time | Developer re-syncs when source changes |
| `context.md` (area context file) | Fetched from GitHub API at runtime by `ContextLoaderWorker` | MQA — updating `inventory.md` etc. takes effect on the next factory run automatically |
| `fragment_library.md` | Generated at runtime by `FragmentScannerWorker` (area-scoped fragments + permissions) | Current — reflects TAF at last clone/pull |
| `test_plan.md`, `scope_manifest.md` | Produced by earlier steps in the same execution | N/A — generated fresh each run |

### The three types of components you write

| Type | How to spot it | YAML entry? | Example |
|---|---|---|---|
| **Non-LLM worker** | Java class `implements AgentWorker` directly | Yes — `type: AGENT` step in the YAML | `ContextLoaderWorker` |
| **LLM worker** | Java class `extends AbstractLlmAgentWorker` | Yes — `type: AGENT` step in the YAML | `E2eSpecAgentWorker` |
| **Post-processor** | Java class annotated `@Component implements StepPostProcessor` | **No** — auto-runs after every agent step, no YAML entry needed | `CypressSanityPostProcessor` |

**Why "agent" and "worker" both appear:** In the YAML, every pipeline step (LLM or not) has `type: AGENT` — that is just the YAML vocabulary for "a step that runs a worker." In Java, the interface is `AgentWorker`. So "worker" and "agent" refer to the same thing from different angles. The `-Agent` or `-Worker` suffix in a class name is just a naming convention and does not tell you whether it calls an LLM — only the supertype does. `TestRailSyncAgentWorker` has "Agent" in its name but is non-LLM; `E2eSpecAgentWorker` has "Agent" and IS LLM. Always check the supertype.

---

## What You Build

### 1. New Maven module: `factory-flow-cypress`

Mirror `factory-flow-test-factory`. One configuration class, one flow descriptor, workers and prompts.

```
factory-flow-cypress/
  pom.xml
  src/main/java/org/folio/factory/cypressfactory/
    CypressFlowConfiguration.java
    worker/
      ContextLoaderWorker.java              (non-LLM; thin-context detection built in)
      FragmentScannerWorker.java            (non-LLM)
      CypressExamplesLoaderWorker.java      (non-LLM; loads classpath:references/examples.md)
      ScenarioAnalysisAgentWorker.java      (LLM; produces scenario_list.md for Gate 0)
      E2eSpecAgentWorker.java               (LLM: generates test cases from confirmed scenarios)
      CypressAutomationAgentWorker.java     (LLM)
      TestRailSyncAgentWorker.java          (non-LLM: push cases after Gate 1 → write real IDs)
      CypressExecutionWorker.java           (process runner, no LLM)
      CypressFinalizerWorker.java           (adapted from Flow A)
  src/main/resources/
    flows/cypress-factory.yaml
    cypress-exec.yaml                       (execution env config — regular + ECS params)
    references/
      examples.md                           (3 golden TestRail cases + anti-patterns)
    prompts/cypress-e2e-spec-agent/
      system.md
      user.md
    prompts/cypress-scenario-analysis/
      system.md
      user.md
    prompts/cypress-automation-agent/
      system.md
      user.md
```

---

### 2. `ContextLoaderWorker` — non-LLM worker (yaml id: `context-loader`)

Runs after triage, before fragment-scanner. Non-LLM.

Reads `scope_manifest.md` and applies the keyword-matching table from `write-testrail-cases/SKILL.md`
(Area Detection Table) against the story summary and component fields. Produces `context.md` —
a verbatim pass-through of the matched area context file, with a small frontmatter header added.

**Area detection logic:**
- Applies keyword matching (case-insensitive) against `scope_manifest.summary` and any `components`/`labels` fields
- Uses the 19-area keyword table defined in the skill
- If multiple areas match: use highest-confidence match (most keywords); list all matched area names in `context.md` frontmatter for Gate 1 reviewer visibility
- If no area matches: produce `context.md` with `area: unknown` and an empty body; log a warning;
  e2e-spec-agent proceeds without area context (still valid, less accurate — LLM falls back to
  its general FOLIO testing knowledge). No fallback/generic context file exists by design.

**`context.md` frontmatter (happy path):**
```markdown
---
areas:
  - inventory
---
[verbatim content of inventory.md]
```

**`context.md` when thin context triggers enrichment:**
```markdown
---
areas:
  - inventory
context_quality: thin
---
[context file content + ## Live Enrichment section]
```

**`context.md` when area is unknown (no keyword match):**
```markdown
---
areas:
  - generic
---
# Cypress Context: generic

_No area-specific context available. Refer to the scope manifest for guidance._
```
The e2e-spec-agent prompt reads `context.md` — an empty/generic body is valid input. The Gate 1
reviewer should notice `areas: [generic]` in the frontmatter and paste the correct context file
content into the body before approving, or accept lower-quality test cases.

`context.md` is included in the Gate 1 review artifacts so the QA reviewer can see which area
was detected. If the detection is wrong, the reviewer edits the `context.md` body directly in
the HITL editor before approving — replacing the wrong area's content with the correct one.
v1 does not re-run detection after Gate 1; any correction is made inline.

**Thin-context enrichment** (triggers on any of 5 conditions: < 10 KBRs, DRAFT marker, missing
`## Exact UI Texts` section, < 3 toasts listed, > 5 known gaps): the worker attempts lightweight
enrichment before finalising `context.md`:
1. GitHub: fetches `package.json` for the matched area's module (permissions) and keyword-filtered
   entries from `en_US.json` (translation strings). 150KB file-size guard; max 15 keywords, 50 translation entries.
2. Jira: fetches linked issues and appends their summaries/descriptions.
All enrichment degrades gracefully when connectors are unconfigured. Appends an enrichment note to
the body and sets `context_quality: thin` in frontmatter when triggered.

**Configuration required:**
```yaml
factory.cypress.folio-ai-repo: ${FACTORY_CYPRESS_FOLIO_AI_REPO:}  # e.g. folio-org/folio-eureka-ai-dev
```

The worker fetches the context file via the GitHub API (single file GET — no full clone):
`GET /repos/{repo}/contents/skills/write-testrail-cases/references/context/{area}.md?ref=main`

Uses the existing `GitHubConnector` (same credentials as the finalizer). If the repo is not
configured or the fetch fails, the worker produces `context.md` with `area: unknown` and
continues — no hard failure.

> **Connector gap:** `GitHubConnector` currently only has write-side methods (`createBranch`,
> `commitFiles`, `createPullRequest`). A `getFileContent(String repo, String branch, String path)`
> method must be added to the interface and its implementation (`DefaultGitHubConnector`) before
> `ContextLoaderWorker` can function. This is analogous to the `getSection(sectionId)` addition
> needed in `TestRailConnector`.

---

### 3. `FragmentScannerWorker` — non-LLM worker (yaml id: `fragment-scanner`)

Runs after context-loader. Produces `fragment_library.md` — area-scoped fragment excerpts and
matching permissions from the stripes-testing TAF.

**TAF is auto-cloned at startup by `TafSyncService`** to `{user.dir}/cypressTafClone` (shallow
clone of `folio-org/stripes-testing`, branch `master`). No configuration required. When the clone
is not yet ready, the worker falls back to the GitHub contents API automatically.
Both `git clone` and `git pull` use `-c core.longpaths=true` to avoid Windows MAX_PATH failures.

**Inputs:** `context.md` (areas from frontmatter) + `scope_manifest.md` (summary for keywords).

**Two-path execution:**

*Local path (TAF clone ready):*
- Resolves each area to its fragment directory (e.g. `check-in` → `checkin`, `fees-fines` → `users`,
  `data-import` → `data_import`) via an explicit map; unmapped areas use the area name directly
- Scores `.js` files: filename keyword match = +3/kw, content keyword match = +1/kw
- Fetches top-2 scoring files in full; lists remaining filenames
- Extracts matching `gui` permission strings from `permissions.js` using area-specific prefix list

*GitHub API fallback (clone not ready):*
- Uses `GitHubConnector.listDirectory()` to list files; filename scoring only
- Permissions section omitted (file too large to fetch via API)

**`fragment_library.md` output (per area):**
- Full source of top-2 scored fragment files under a `### path` heading
- Bullet list of other fragment filenames in the area
- `### Permissions` section: exact `gui` strings for use in test case preconditions

This artifact feeds both `e2e-spec-agent` (permission names in preconditions) and
`cypress-automation-agent` (fragment imports and method names).

> **Fragment directory naming:** TAF uses mixed conventions — `bulk-edit` (hyphenated),
> `checkin` (no separator), `data_import` (underscore), `marcAuthority` (camelCase).
> The explicit `AREA_TO_DIR` map in `FragmentScannerWorker` handles all known mismatches.

---

### 4. `E2eSpecAgentWorker` — LLM worker (yaml id: `e2e-spec-agent`)

**Do not write this from scratch.** The content already exists in the MQA skill.

**Build the system prompt from `write-testrail-cases/SKILL.md` in order:**

1. **Core principle and writing guidelines** — embed verbatim:
   - Core Principle ("A test case exists to verify a business rule, not a click path")
   - Test Case Structure (title format, preconditions, steps)
   - Scenario Coverage Checklist
   - Self-Review Gate (the 10-point checklist)
   - Step Patterns (the copy-paste patterns section)

2. **Scenario Analysis format** — embed the Scenario Analysis Format section verbatim.
   The factory's Gate 1 is the equivalent of the skill's "wait for confirmation" step — the LLM
   should produce the scenario analysis as part of `test_plan.md`, not as a separate turn.

3. **Metadata field definitions** — embed the Metadata Fields Reference table. In particular:
   - `custom_automation_type`: always `2` (Manual) — the Cypress spec is the automation artifact;
     the TestRail case itself is always a manual test case record
   - `labels`: always `[67]` (the AI label on foliotest.testrail.io)
   - `custom_ecs_enabled`: derived from `scope_manifest.md` ECS flag
   - `custom_dev_team`: derived from Jira team field via the mapping table
   - `custom_test_group`: derived per case (Smoke=1, Critical Path=2, Extended=3)

4. **Area detection note** — the context file is already loaded into the user prompt by
   `ContextLoaderWorker`; the system prompt tells the LLM to treat it as authoritative for
   exact UI texts, capability set names, and business rule numbering.

5. **`examples.md`** — resolved by `CypressExamplesLoaderWorker`. Fetches from
   `folio-eureka-ai-dev` GitHub repo at runtime (branch `master`); falls back to
   `classpath:references/examples.md`. Injected into the user prompt via `{{examples}}`.

**What the prompt must add beyond SKILL.md:**

- Output format: YAML-frontmattered `test_plan.md` with one block per test case
- Per-case `automatable` flag (`true/false`) — the LLM sets an initial value based on obvious
  constraints (multiple browser tabs, external auth flows, MFA, OS interactions → false). This
  flag is **not final** — the Gate 1 reviewer can and should override it. After Gate 1 approval
  the flag is treated as final by all downstream steps: automatable cases get a Cypress spec;
  non-automatable cases go to TestRail only (manual execution forever).
- No ID assignment at this stage — TestRail assigns real `C######` IDs after Gate 1 approval.
  Cases in `test_plan.md` have titles and automatable flags; IDs are added by `testrail-sync-agent`.
- The scenario analysis block goes at the top of `test_plan.md` so Gate 1 reviewers see the
  coverage rationale alongside the cases themselves.

The `worker_id` `e2e-spec-agent` in `cypress-factory.yaml` is the only coupling point for
this step. If MQA significantly restructures the skill or a different markdown skill replaces it,
only the system prompt needs updating — the YAML descriptor and all downstream workers stay unchanged.

**User prompt template** receives:
- `scope_manifest.md` (from triage)
- `context.md` (from ContextLoaderWorker — verbatim area context file)
- The Jira story body (already in scope_manifest or passed directly)

**The worker class — `E2eSpecAgentWorker`:**

Extends `AbstractLlmAgentWorker`. Reads `scope_manifest.md` and `context.md`, calls
`callForEntity(vars, TestPlan.class)`, and returns `AgentResult.of("test_plan.md", ...)`.
You need a `TestPlan` record in the model package — same idea as Flow A's `TestPlan` but
fields match the Cypress flow's case structure (`id`, `title`, `automatable`, `tier`,
`ecs_enabled`, `steps`, etc.). See the Implementation Reference section for the LLM worker
pattern; the Java shape is identical to `CypressAutomationAgentWorker` — just different
inputs, record type, and output artifact name.

---

### 5. `CypressAutomationAgentWorker` — LLM worker (yaml id: `cypress-automation-agent`)

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

3. **Fragment library injection** — the `fragment_library.md` artifact from `FragmentScannerWorker`
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
  `test_plan.md` (title, scope, feature area) and `scope_manifest.md` as the folder structure
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
  from the Jira story's team field during triage and stored in `scope_manifest.md`; the
  e2e-spec-agent copies it into `test_plan.md`. If the value is `unknown`, use
  `'N/A - please fill'` as the tag and add this banner at the top of the spec:
  ```javascript
  // ⚠️  TEAM TAG UNRESOLVED — Jira team value did not match any known team in the mapping table.
  // Replace 'N/A - please fill' in the tags array with the correct team tag before merging.
  ```

---

### 6. `TestRailSyncAgentWorker` — non-LLM worker (yaml id: `cypress-testrail-sync`)

> **Execution order note:** In the YAML this step runs between Gate 1 and `cypress-automation-agent`
> (section 5). Sections here are numbered by implementation complexity, not pipeline order.

Non-LLM. Runs **after Gate 1 approval**, before spec generation. Uses the existing Java
`TestRailConnector` (not the MCP server — that is IDE-only).

Reads `test_plan.md` (Gate-1-approved cases). Pushes **all cases** to TestRail — automatable and
non-automatable alike — and collects real `C######` IDs for each. Outputs an updated `test_plan.md`
with the real IDs written back into each case entry. The downstream `cypress-automation-agent` reads
this updated `test_plan.md` and uses the real IDs directly when generating specs — no find-replace
step needed anywhere in the pipeline.

**Reference:** The custom MCP server (`testrail-mcp-server/index.js`) is a useful data contract
reference for which TestRail fields to set and retrieve. The factory uses the Java connector.

#### Data the worker sets on case creation

| TestRail field | Value source | Notes |
|---|---|---|
| `section_id` | `testrail_section` from `scenario_list.md` frontmatter (Gate-0 confirmed); `scope_manifest.md` override takes priority if present; falls back to `FACTORY_CYPRESS_TESTRAIL_SECTION_ID` | Accepts path string (resolved via API) or numeric ID |
| `custom_test_group` | tier from `test_plan.md` per case | 1=smoke, 2=criticalPath, 3=extendedPath |
| `custom_dev_team` | `team` field from `scope_manifest.md` → mapped: Firebird=3, Folijet=4, Spitfire=6, Thunderjet=8, Vega=9, Volaris=13, Citation=18, Corsair=19, Eureka=21, Promin=27 | omitted if team absent or unknown |
| `custom_ecs_enabled` | ECS flag from `scope_manifest.md` | |
| `custom_automation_type` | `2` (Manual) for all cases | The Cypress spec is the automation artifact; the TestRail case is always a manual test case record |
| `labels` | `[id of testrailLabel]` | Label resolved at runtime via `getOrCreateLabel(testrailLabel)`; label title configured by `FACTORY_CYPRESS_TESTRAIL_LABEL` (default: `cypress-flow-delete-case`) |
| `refs` | `issue_key` from `scope_manifest.md` | Jira issue key (e.g. `UIIN-2156`) linked on every created case; omitted when blank |

#### Real ID writeback

The worker maintains a `TC-X → C######` mapping built during case creation. After all cases
are pushed it does a string replacement in the `test_plan.md` body — each heading changes from
`## TC-1: Create order` to `## C627249: Create order`. The frontmatter is passed through
unchanged (it carries only lightweight metadata: `issue_key`, `team`, `ecs_enabled`,
`case_count`). The updated `test_plan.md` is the sole output; no spec bundle is read or modified here.

The body is the single source of truth for case data. Downstream workers parse cases from body
headings using the same `## ID: Title` pattern — after this step, `tc.id()` returns `C627249`.

**Partial failure caveat:** if `addCaseWithMetadata` throws for one case, that `TC-X` token
stays unreplaced in the body. The downstream `cypress-automation-agent` would then receive
`TC-X` as a case ID instead of a real TestRail ID, silently breaking the spec-to-TestRail link
for that case. See Phase 2 Risks below.

#### Section hierarchy for `describeBlocks`

After creating each case, fetch the assigned section hierarchy the same way the MCP server does:
walk `parent_id` up to `depth == 0` via `GET /api/v2/get_section/{id}`. Store as
`describeBlocks` in the mapping for use in the finalizer PR description (informational only —
the specs already have correct `describe()` nesting from the generation step).

The Java connector will need a `getSection(sectionId)` method added.

---

### 7. `CypressSanityPostProcessor` — post-processor (no YAML entry — auto-runs after every step)

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

### 8. `CypressExecutionWorker` — non-LLM worker (yaml id: `cypress-execution-agent`)

**Advisory mode** (default when `FACTORY_TAF_HAS_ENV_CONFIG` is not `true`): produces `test_results.md` with `mode: ADVISORY`, all cases `NOT_EXECUTED`.

**Execution mode**: reads `test_scripts.md` (Cypress bundle with real C###### IDs) and
`test_plan.md` (to know the `ecs_enabled` flag per case, used to split specs into regular vs ECS
buckets). Writes specs and any new fragment stubs to their final paths in the TAF root
(`cypress/e2e/` for specs, `cypress/support/fragments/` for fragments), then runs Cypress.
Both artifacts must be declared in the step's `inputs:` in the YAML.

#### Why `environments.js` is not used

`environments.js` is gitignored — the factory cannot rely on it being present or correctly
configured on the execution host. Instead, the worker passes all env params explicitly via
command-line flags, bypassing the TAF's local-dev mechanism entirely.

#### Split runs: regular vs ECS

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

#### Allure report output and serving

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

#### `cypress-exec.yaml` — the execution config file

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

#### Three-tier precedence (highest wins)

```
1. Gate 2 per-run override   — reviewer types key=val,key=val into the review form
2. Deploy-time env vars      — FACTORY_CYPRESS_REGULAR_ENV / FACTORY_CYPRESS_ECS_ENV (key=val string)
3. cypress-exec.yaml         — checked-in baseline
```

Tier 2 env vars let CI/CD inject real credentials without modifying the committed file.
Tier 1 handles one-off edge cases without touching config or restarting the server.

#### Gate 2 execution section

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

### 9. `CypressFinalizerWorker` — non-LLM worker (yaml id: `cypress-finalizer`)

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

## Reuse Summary

| Component | Source | Action |
|---|---|---|
| `triage-agent` worker + prompts | Flow A | Reuse unchanged |
| `E2eSpecAgentWorker` / `e2e-spec-agent` | Flow A `TestSpecAgentWorker` (copy as template) | **New class, new worker ID** — copy Java structure; system prompt built from write-testrail-cases content; Flow A's `test-spec-agent` is untouched |
| HITL gate mechanism | Core engine | Reuse unchanged |
| HITL web console + review UI | `factory-app` | Reuse unchanged |
| TestRail Java connector | `factory-connectors` | Reuse; add `getSection(sectionId)` method for hierarchy lookup |
| GitHub connector | `factory-connectors` | Reuse; added `getFileContent(repo, branch, path)` (context file loading) and `listDirectory(repo, branch, path)` (fragment fallback) |
| `ArtifactStore`, `ExecutionEngine`, poller | `factory-core` | Reuse unchanged |
| `FrontmatterCodec`, `ScriptBundleCodec` | `factory-agents` | Reuse unchanged; `CypressSanityPostProcessor` checks `.cy.js` extension (the codec itself supports any file type) |
| `write-testrail-cases/SKILL.md` writing guidelines | MQA skill | Embed in `e2e-spec-agent` system prompt (source of truth for case authoring) |
| Area context files (19 × `references/context/*.md`) | MQA skill | Loaded at runtime by `ContextLoaderWorker` — auto-sync, no re-embed needed |
| `CLAUDE.md` content | TAF | Embed in `cypress-automation-agent` system prompt |
| `ecs-user-setup/SKILL.md` content | TAF `.github/skills/` | Embed in `cypress-automation-agent` system prompt |
| Agent logic from `.github/agents/*.md` | TAF | Inform worker design; not imported directly |
| TestRail MCP server | TAF (IDE tool) | Not used by factory; stays in IDE |

---

## Environment Variables

| Variable | Purpose |
|---|---|
| TAF path | Auto-managed: `TafSyncService` clones to `{user.dir}/cypressTafClone` on startup — no env var needed |
| `FACTORY_CYPRESS_FOLIO_AI_REPO` | GitHub repo (`owner/name`) containing the FOLIO AI skills (e.g. `folio-org/folio-eureka-ai-dev`); used by `ContextLoaderWorker` to fetch area context files and by `CypressExamplesLoaderWorker` to fetch `examples.md` at runtime |
| `FACTORY_CYPRESS_TARGET_REPO` | GitHub repo (`owner/name`) where generated Cypress specs are committed (e.g. `folio-org/stripes-testing`) |
| `FACTORY_CYPRESS_BASE_BRANCH` | Base branch in `TARGET_REPO` (default: `master`) |
| `FACTORY_CYPRESS_TESTRAIL_SECTION_ID` | Fallback TestRail section ID (long integer); used when `testrail_section` is absent from both `scenario_list.md` and `scope_manifest.md` |
| `FACTORY_CYPRESS_TESTRAIL_CASE_TYPE_ID` | TestRail case type ID (e.g. `1`=Functional on foliotest); optional — omit to leave type unset |
| `FACTORY_CYPRESS_TESTRAIL_PRIORITY_ID` | TestRail priority ID (default: `3`=Medium) |
| `FACTORY_CYPRESS_TESTRAIL_LABEL` | Label title applied to every created case (default: `cypress-flow-delete-case`); resolved to a label ID at runtime via `getOrCreateLabel` |
| `FACTORY_CONNECTORS_TESTRAIL_BASE_URL` | TestRail instance base URL |
| `FACTORY_CONNECTORS_TESTRAIL_USERNAME` | TestRail username (email) |
| `FACTORY_CONNECTORS_TESTRAIL_API_KEY` | TestRail API key |
| `FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID` | TestRail project ID (integer) |
| `FACTORY_CONNECTORS_TESTRAIL_SUITE_ID` | TestRail suite ID (integer; required on multi-suite projects) |
| `FACTORY_CONNECTORS_GITHUB_TOKEN` | GitHub personal access token (read for context/examples fetch; write for finalizer PR) |
| `FACTORY_CONNECTORS_GITHUB_BASE_URL` | GitHub API base URL (default: `https://api.github.com`; override for GHE) |
| `FACTORY_TAF_HAS_ENV_CONFIG` | Set `true` to enable execution mode in `CypressExecutionWorker` (Phase 3+) |
| `FACTORY_CYPRESS_EXEC_CONFIG` | Path to `cypress-exec.yaml`; defaults to bundled `src/main/resources/cypress-exec.yaml` |
| `FACTORY_CYPRESS_REGULAR_ENV` | Deploy-time override for regular-test env params (`key=val,key=val`); merged on top of `cypress-exec.yaml` regular block |
| `FACTORY_CYPRESS_ECS_ENV` | Deploy-time override for ECS-test env params (`key=val,key=val`); merged on top of `cypress-exec.yaml` ecs block |

---

## Open Tasks / Prerequisites

These gaps must be resolved before or during Phase 1. They are not implementation details — they
are missing pieces that will block the flow from working correctly if left unaddressed.

---

### TASK: Add `ecs_enabled` to `ScopeManifest`

**Status:** DONE — `ecsEnabled` field present in `ScopeManifest.java`; triage prompt instructs detection from labels/description.

**Problem:** The plan references `ecs_enabled` throughout (context loader, test-spec agent prompt,
E2E spec agent, cypress-execution, YAML). However, the Java record
`factory-flow-test-factory/.../model/ScopeManifest.java` has no such field today:

```java
// Current fields: issueKey, summary, components, endpoints, riskLevel, ambiguities, analysis
// Missing: ecs_enabled
```

Without this field, `scope_manifest.md` will never carry the ECS flag, and any worker or prompt
that reads it will have no signal to split cases/specs by tenant type.

**Required changes:**

1. **`ScopeManifest.java`** — add field:
   ```java
   boolean ecsEnabled,
   ```

2. **Triage agent `system.md`** — add explicit instruction to set the flag:
   ```
   ## ECS Detection
   Set ecs_enabled: true if ANY of the following is present in the Jira story:
   - label "ecs" or "multi-tenant", "consortia'
   - AC or description language like "as a member/central library/tenant/affiliation", "consortial", "shared/local record/instance"
   Otherwise set ecs_enabled: false.
   ```

3. **Triage agent `user.md`** — no change needed; it already passes raw `{{issue_json}}` which
   contains labels, components, and description.

4. **Verify** the field appears in `scope_manifest.md` after a triage run. Example expected output:
   ```yaml
   issueKey: ERM-1001
   components: [ERM]
   ecs_enabled: true
   ```

**Who:** whoever implements Phase 1, Step 3 (fragment scanner) onwards — the ECS field is first
consumed by the context-loader in Phase 1, so this task must be done before Phase 1 end-to-end
testing.

---

## Implementation Phases

### Phase 1 — Test cases + Gate 1 (no Cypress yet)

**Goal: Gate 1 works end-to-end; cases published to TestRail with real IDs after approval.**

**Implemented steps:**

1. ✅ Create `factory-flow-cypress` Maven module
2. ✅ Implement `ContextLoaderWorker` (area detection + context file load + thin-context flag)
3. ✅ Implement `TafSyncService` (auto-clone to `cypressTafClone/`) + `FragmentScannerWorker` (keyword scoring, GitHub API fallback, permissions matching → `fragment_library.md`)
4. ✅ Implement `CypressExamplesLoaderWorker` (runtime GitHub fetch + classpath fallback)
5. ✅ Implement `ScenarioAnalysisAgentWorker` (LLM; categorized scenario proposals)
6. ✅ Write `cypress-scenario-analysis/system.md` + `user.md`
7. ✅ Write `cypress-e2e-spec-agent/system.md` (from SKILL.md + House Style + Step Patterns + self-review)
8. ✅ Write `cypress-e2e-spec-agent/user.md` (with `{{examples}}` + `{{scenario_list}}` sections)
9. ✅ Implement `TestRailSyncAgentWorker` (push cases → collect real IDs → write back)
10. ✅ Write `cypress-factory.yaml`:
    triage → context-load → fragment-scan → examples-load → scenario-analysis →
    gate-0-scenarios → test-spec → gate-1-review → testrail-sync
    Gate-0 reviewed: [scenario_list.md, scope_manifest.md, context.md]
    Gate-1 reviewed: [test_plan.md, scenario_list.md, scope_manifest.md, context.md]
11. ✅ Wire `CypressFlowConfiguration`, add to `factory-app/pom.xml`

**Milestone:** Gate 0 confirms scenario coverage; Gate 1 reviews test cases; after approval,
cases appear in TestRail and `test_plan.md` carries real `C######` IDs.

**Verify:** Open `/executions/{id}`. Gate 0 shows `scenario_list.md` with categorized S-NN
proposals. Gate 1 shows `context.md` with `areas:` frontmatter and `test_plan.md` with body
sections `## TC-X: Title` per case, each with automatable/tier/preconditions/steps. After Gate 1
approval: TestRail cases created with the configured AI label and `refs` set to the Jira key;
`test_plan.md` body headings updated to `## C######: Title`.

---

### Phase 2 — Cypress spec generation + Gate 2

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

**Iteration expected:** Plan for 3-5 prompt tuning rounds for both `e2e-spec-agent` and
`cypress-automation-agent`. For `e2e-spec-agent`, the context files + SKILL.md writing
guidelines are the highest-leverage knob — they provide FOLIO-specific factual grounding
(exact UI texts, business rules, capability sets) that the LLM cannot reliably produce from
training data alone. Few-shot examples are the highest-leverage knob for `cypress-automation-agent`.

---

### Phase 3 — Execution and finalize

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

## Risks and Mitigations

### Fragment accuracy (highest LLM quality risk for spec generation)
The `FragmentScannerWorker` mitigates this significantly. Even with the index, the LLM may use
wrong method names. The Gate 2 HITL review and the resulting PR review in stripes-testing are
the safety nets — not the automated checks.

### Area detection accuracy (new risk from context-loader)
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

### `e2e-spec-agent` quality
The `write-testrail-cases/SKILL.md` writing guidelines and area context files are a significant
upgrade from the generic Flow A prompts. The context files provide verbatim UI texts, verified
business rules, and exact capability set names — the factual grounding that previously had to
come from the LLM's training data (unreliable for specific FOLIO versions).

Remaining mitigations:
- `automatable: true/false` flag per case — Gate 1 is the main quality control; QA confirms or overrides
- Non-automatable cases still go to TestRail; just skipped by the automation agent
- The `test_scripts.md` bundle will have fewer specs than test cases in `test_plan.md`; this is expected (non-automatable cases are skipped) and noted in `sync_report.md`

### `examples.md` gap — RESOLVED
`CypressExamplesLoaderWorker` fetches `examples.md` from `folio-eureka-ai-dev` at runtime
(branch `master`); classpath fallback when GitHub is unavailable. Injected via `{{examples}}`
in `user.md`. Classpath copy at `src/main/resources/references/examples.md` is updated manually.

---

## Effort Estimate

| What | Estimate |
|---|---|
| `factory-flow-cypress` skeleton + pom.xml | 0.5 day |
| `ContextLoaderWorker` (area detection + context file load) | 0.5 day |
| `FragmentScannerWorker` (scan + export parse) | 1 day |
| `e2e-spec-agent/system.md` (from write-testrail-cases + examples) | 1-2 days |
| `TestRailSyncAgentWorker` | 1 day |
| `cypress-factory.yaml` (all phases) | 0.5 day |
| `cypress-automation-agent/system.md` (initial + 3-5 tuning rounds) | 2-3 days |
| `CypressAutomationAgentWorker` + `CypressSanityPostProcessor` | 1 day |
| `CypressExecutionWorker` (advisory mode) | 0.5 day |
| `CypressExecutionWorker` (real execution) | 1 day |
| `CypressFinalizerWorker` | 1 day |
| Phase 1-3 end-to-end testing | 2-3 days |
| Allure report serving endpoint in `factory-app` (optional) | 0.5 day |

**Total: ~12-14 days for a working Phase 1-3 flow** (+ 0.5 day optional Allure serving)

Starting from `write-testrail-cases/SKILL.md` (MQA's tested instructions) for e2e-spec-agent
and from `CLAUDE.md` (TAF's tested instructions) for cypress-automation-agent saves meaningful
prompt engineering time and should produce better first-run quality than starting from zero.
Context files auto-sync at runtime, so the area-specific factual grounding stays current with
no maintenance burden on the factory team.

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

### Non-LLM worker: `ContextLoaderWorker`

Copy the shape of `TestExecutionWorker`. No `ChatClient`, no prompt files — just `implements AgentWorker`.

```java
// factory-flow-cypress/src/main/java/.../worker/ContextLoaderWorker.java

public class ContextLoaderWorker implements AgentWorker {

    public static final String ID = "context-loader";

    private final CypressFlowProperties properties;   // holds FACTORY_MQA_SKILL_REPO + branch
    private final GitHubConnector gitHub;             // reused — same connector as finalizer
    private final FrontmatterCodec frontmatterCodec;  // shared codec from factory-agents

    public ContextLoaderWorker(CypressFlowProperties properties,
                               GitHubConnector gitHub,
                               FrontmatterCodec frontmatterCodec) {
        this.properties = properties;
        this.gitHub = gitHub;
        this.frontmatterCodec = frontmatterCodec;
    }

    @Override
    public String id() { return ID; }

    @Override
    public AgentResult execute(AgentContext context) throws AgentExecutionException {

        // 1. Read your input artifact — always requireInput(), never null-check manually
        String scopeManifest = context.requireInput("scope_manifest.md").content();

        // 2. Do your logic
        String detectedArea = detectArea(scopeManifest);            // keyword matching
        String contextBody  = loadContextFile(detectedArea);        // read .md from disk

        // 3. Assemble output as Markdown-with-YAML-frontmatter
        Map<String, Object> meta = Map.of(
            "detected_area", detectedArea,
            "confidence",    "HIGH",
            "context_file",  detectedArea + ".md"
        );
        String output = frontmatterCodec.render(meta, contextBody);

        // 4. Return — key must match the artifact name declared in cypress-factory.yaml
        return AgentResult.of("context.md", output);
    }

    private String detectArea(String scopeManifest) {
        // keyword matching against the 19-area table from SKILL.md
        // return "inventory", "orders", etc., or "unknown" if nothing matches
    }

    private String loadContextFile(String area) throws AgentExecutionException {
        // fetch single file from GitHub — no local checkout needed
        String path = "skills/write-testrail-cases/references/context/" + area + ".md";
        try {
            return gitHub.getFileContent(properties.mqaSkillRepo(),
                                         properties.mqaSkillBranch(), path);
        } catch (NotFoundException e) {
            return "";   // produce context.md with empty body; log a warning
        } catch (Exception e) {
            throw new AgentExecutionException("Cannot read context file: " + path, e);
        }
    }
}
```

`FragmentScannerWorker` follows the exact same shape: reads `context.md` (areas) and
`scope_manifest.md` (keywords), uses `TafSyncService.tafRoot()` for local filesystem scoring
or `GitHubConnector.listDirectory()` as fallback, returns `AgentResult.of("fragment_library.md", output)`.

`TestRailSyncAgentWorker` also follows this shape but additionally calls the `TestRailConnector`
— look at `TestFactoryFinalizerWorker.syncTestRail()` for the exact connector API. It writes real
IDs back into `test_plan.md` (one `id: C######` field per case); no bundle is touched.

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
        String fragmentLibrary = context.requireInput("fragment_library.md").content();

        // 2. Call the LLM — vars map becomes {{placeholder}} substitutions in user.md
        //    callForEntity parses the LLM's JSON response into a ScriptBundle record
        ScriptBundle bundle = callForEntity(
            Map.of(
                "test_plan",       testPlan,
                "fragment_library", fragmentLibrary
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
src/main/resources/prompts/cypress-automation-agent/user.md     ← template with {{test_plan}}, {{fragment_library}}
```

The base class loads them by `workerId` automatically — the file names are the contract.
`{{placeholder}}` in the `.md` file is replaced with whatever key you put in the `Map.of(...)`.

---

### LLM worker: `E2eSpecAgentWorker`

Same shape as `CypressAutomationAgentWorker` above — `extends AbstractLlmAgentWorker`, same
constructor pattern, same `callForEntity` call. Only three things differ:

```java
public static final String ID = "e2e-spec-agent";

// Inputs: scope_manifest + area context (not test_plan + fragment_library)
String scopeManifest = context.requireInput("scope_manifest.md").content();
String areaContext   = context.requireInput("context.md").content();

// Different record type and output artifact
TestPlan plan = callForEntity(
    Map.of("scope_manifest", scopeManifest, "context", areaContext),
    TestPlan.class   // ← your model record; fields: id, title, automatable, tier, ecs_enabled, steps…
);
return AgentResult.of("test_plan.md", frontmatterCodec.render(metadata, renderBody(plan)));
```

Prompt files: `prompts/e2e-spec-agent/system.md` + `user.md` (placeholders `{{scope_manifest}}`,
`{{context}}`).

`TestPlan` is a new record you define in the model package — same idea as Flow A's `TestPlan`
but shaped to match the Cypress flow's case fields.

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

Copy the shape of `ContextLoaderWorker` (non-LLM), but also inject `TestRailConnector`.

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
        String scopeManifestRaw    = context.requireInput("scope_manifest.md").content();

        // 1. Commit all files (specs + any fragment stubs) to a new branch in stripes-testing
        String issueKey = parseIssueKey(scopeManifestRaw);   // always present in scope_manifest.md
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

### Wiring it all: `CypressFlowConfiguration`

Every worker needs to be constructed and returned as a `@Bean`. This is also where `ChatClient`
is built and injected into LLM workers.

```java
// factory-flow-cypress/src/main/java/.../CypressFlowConfiguration.java

@Configuration
public class CypressFlowConfiguration {

    // Non-LLM workers — just construct them
    @Bean
    public ContextLoaderWorker contextLoaderWorker(CypressFlowProperties props,
                                                    GitHubConnector gitHub,
                                                    FrontmatterCodec codec) {
        return new ContextLoaderWorker(props, gitHub, codec);
    }

    @Bean
    public FragmentScannerWorker fragmentScannerWorker(CypressFlowProperties props,
                                                        FrontmatterCodec codec) {
        return new FragmentScannerWorker(props, codec);
    }

    // LLM workers — need a ChatClient; copy how FlowAConfiguration builds it
    @Bean
    public CypressAutomationAgentWorker cypressAutomationAgentWorker(
            ChatClient.Builder chatClientBuilder,
            ScriptBundleCodec bundleCodec) {
        ChatClient chatClient = chatClientBuilder.build();
        return new CypressAutomationAgentWorker(chatClient, bundleCodec);
    }

    // ... same pattern for E2eSpecAgentWorker, TestRailSyncAgentWorker,
    // CypressExecutionWorker, CypressFinalizerWorker
}
```

Look at `FlowAConfiguration` in `factory-flow-test-factory` — it's 60 lines and shows exactly
this pattern for every Flow A worker.

---

### The flow YAML — how to declare a step

Once the worker bean exists, add a step to `cypress-factory.yaml`. Match
`worker_id` to the worker's `ID` constant exactly.

```yaml
# src/main/resources/flows/cypress-factory.yaml

id: cypress-factory
version: 1.0.0
retry_policy:
  max_attempts: 3
  backoff_seconds: [30, 120, 300]

steps:
  - step_id: triage
    type: AGENT
    worker_id: triage-agent          # reused from Flow A — already registered
    inputs: [$trigger]
    outputs: [scope_manifest.md]

  - step_id: context-load
    type: AGENT
    worker_id: context-loader        # matches ContextLoaderWorker.ID
    inputs: [scope_manifest.md]
    outputs: [context.md]

  - step_id: fragment-scan
    type: AGENT
    worker_id: fragment-scanner
    inputs: [context.md, scope_manifest.md]
    outputs: [fragment_library.md]

  - step_id: test-spec
    type: AGENT
    worker_id: e2e-spec-agent       # new worker — copy Java shape from TestSpecAgentWorker
    inputs: [scope_manifest.md, context.md]
    outputs: [test_plan.md]

  - step_id: gate-1
    type: HITL_GATE
    review_artifacts: [test_plan.md, context.md, scope_manifest.md]

  - step_id: testrail-sync
    type: AGENT
    worker_id: cypress-testrail-sync
    inputs: [test_plan.md]
    outputs: [test_plan.md]      # same artifact name, now with real C###### IDs written in

  - step_id: cypress-automation
    type: AGENT
    worker_id: cypress-automation-agent
    inputs: [test_plan.md, fragment_library.md]   # test_plan.md now carries real C###### IDs
    outputs: [test_scripts.md]

  - step_id: gate-2
    type: HITL_GATE
    review_artifacts: [test_scripts.md, test_plan.md]

  - step_id: cypress-execution
    type: AGENT
    worker_id: cypress-execution-agent
    inputs: [test_scripts.md, test_plan.md]   # test_plan.md needed for ecs_enabled split
    outputs: [test_results.md]

  - step_id: cypress-finalize
    type: AGENT
    worker_id: cypress-finalizer
    inputs: [test_scripts.md, test_results.md, scope_manifest.md]   # scope_manifest.md for issueKey
    outputs: [sync_report.md]
```

The engine reads `inputs:` to decide which artifacts to fetch from the store and hand to
the worker. An artifact that isn't in `inputs:` is invisible to that worker — this is
intentional (scope boundary). `outputs:` declares what the worker must return; the engine
throws if a declared output is missing from `AgentResult`.

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

# Suggested Next Steps (Post Phase 3)

> These are extensions to consider after the Phase 1-3 flow is working and tested end-to-end.
> Do not build any of this until the base pipeline is stable.

### Phase 4 — Automatic fix loop for Cypress failures

Generated specs will have failures on first run — wrong fragment method names, wrong assertion
text, missing imports, timing issues. Phase 3 leaves these as "human fix required." A fix loop
would let the factory self-repair the obvious ones before handing off.

#### How it would work

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

#### `FailureAnalyzerWorker` — non-LLM, the critical gating step

Reads Allure JSON files from `allure-results/` and `test_plan.md`. For each failing test,
produces a `failure_report.md` entry with:
- Which step failed and the raw error/assertion message
- The test's original intent (from `test_plan.md`)
- A category tag:
  - `FIXABLE` — structural issues an LLM can reliably fix: import errors, unknown method names
    (cross-referenced against `fragment_library.md`), assertion text mismatches where the actual
    value is visible in the error, wrong path depth in imports
  - `ENV_ISSUE` — credential failures, network timeouts, missing test data, tenant not found —
    no code change can fix these; skip them
  - `UNCLEAR` — everything else; pass to the fix agent but with low confidence

This step prevents the LLM from burning iterations on environment problems that will never be
fixed by editing code.

#### `SpecFixAgentWorker` — LLM, surgical fixes only

Key constraints the prompt must enforce explicitly:
- Only modify files listed in `failure_report.md` as `FIXABLE` or `UNCLEAR` — do not touch
  passing tests
- Output a ScriptBundle containing **only the modified files** — a diff, not a full rewrite
- Fixing a failure by removing or weakening an assertion is not a fix — flag it as `UNFIXABLE`
  instead
- If a fix requires a new fragment method that doesn't exist in `fragment_library.md`, generate
  a fragment stub and include it in the bundle (same pattern as the automation agent)

Re-run command targets only failing specs:
```bash
npx cypress run -b chrome --spec <previously-failing-spec-paths> ...
```

#### What to report

After N iterations, any still-failing tests are surfaced in `sync_report.md` as
`unfixed_failures` — a list of case IDs with their final error and category. These appear
prominently in the Jira comment so engineers know exactly what needs manual attention.

#### What's realistically fixable

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

#### Prevention is cheaper than repair

Before building Phase 4, consider expanding `CypressSanityPostProcessor` to catch more
structural issues *before* execution: unknown fragment references (cross-check against
`fragment_library.md`), wrong import depth (verify against bundle file path), tag format
violations. Better prevention means fewer failures reach the fix loop in the first place.
