# Quickstart — Cypress Factory flow

The detailed reference for running `cypress-factory`. New to the flow? Start with the short
[GETTING_STARTED.md](GETTING_STARTED.md). Commands are for Bash (Git Bash on Windows), and they
run from the **repository root**, not from this folder.

Prerequisites:
- Java 21 and Maven 3.9+.
- Docker or Podman. Start Postgres with `docker compose up -d postgres`.
- For the automation steps:
  - the `claude` CLI on PATH;
  - node + yarn;
  - Chrome, for spec runs only.

## 1 — Rebuild

```bash
mvn install -DskipTests
```

## 2 — Load env vars

Copy [.env.example](../.env.example) to `.env` (gitignored). Every variable is documented
there; the Cypress section lists the flow's knobs with their defaults. The ones you must set:

```bash
# LLM - direct Anthropic key. Also what the automation steps' claude CLI uses.
ANTHROPIC_API_KEY=sk-ant-api03-...
# Jira (optional - without it use the inline-story trigger below)
FACTORY_CONNECTORS_JIRA_BASE_URL=https://folio-org.atlassian.net
FACTORY_CONNECTORS_JIRA_EMAIL=your@email.com
FACTORY_CONNECTORS_JIRA_API_TOKEN=...
# TestRail (optional - without it the sync is skipped; automation needs it)
FACTORY_CONNECTORS_TESTRAIL_BASE_URL=https://foliotest.testrail.io
FACTORY_CONNECTORS_TESTRAIL_USERNAME=your@email.com
FACTORY_CONNECTORS_TESTRAIL_API_KEY=...
FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID=14
FACTORY_CONNECTORS_TESTRAIL_SUITE_ID=21
# GitHub: API fallbacks, context enrichment, and the PR step (push access to the fork)
FACTORY_CONNECTORS_GITHUB_TOKEN=ghp_...
FACTORY_CYPRESS_TARGET_REPO=<you>/stripes-testing   # a fork; unset = approve at gate 2 opens no PR
# Automation-only runs (partial-run endpoint) and prompt dumps
FACTORY_DEBUG_ENABLED=true
```

Defaults worth knowing:

| Variable | Default | Notes |
|---|---|---|
| `FACTORY_CYPRESS_AUTOMATION_MAX_CASES` | `2` | Per-run cost cap |
| `FACTORY_CYPRESS_AUTOMATION_RUN_SPECS` | `false` | Not yet verified end to end. Keep it off unless you're testing it. |
| `FACTORY_CYPRESS_AUTOMATION_CLI_MODEL` / `_CLI_EFFORT` | `claude-sonnet-5-5` / `high` | Pinned; never read from `~/.claude/settings.json` |

**LLM via CodeMie SSO instead of a key.** Start the CodeMie proxy and leave
`ANTHROPIC_API_KEY` unset: a set key bypasses CodeMie. Case generation then works. The
automation steps run the `claude` CLI, which inherits `ANTHROPIC_API_KEY` when it is set and
otherwise falls back to its own login, so check `claude -p hi` works in the same shell first.

Then source it:

```bash
set -a; source .env; set +a
```

`set -a` auto-exports every variable sourced so child processes (`mvn`, `curl`) inherit them.

## 3 — Start the factory

```bash
mvn spring-boot:run -pl factory-app
```

The app runs from `factory-app/`, so every folder below is under it. On first startup two
background clones run (shallow, ~30 s each, non-blocking):

- `TafSyncService` → `folio-org/stripes-testing` into `factory-app/cypressTafClone/` (Cypress
  fragments)
- `SkillWorkspaceService` → `FACTORY_CYPRESS_FOLIO_AI_REPO` into `factory-app/skillsClone/`
  (SKILL.md, `examples.md`, the 38 area context files)

A third clone, `factory-app/cypressTafWork/`, is made the first time an automation step runs (a
minute or two, plus a one-time `yarn install` of several minutes). Automation writes only there,
on one branch per run, so `cypressTafClone/` stays a clean read-only copy for the fragment
lookup. The branch is pushed only when you approve gate 2 (to `FACTORY_CYPRESS_TARGET_REPO`).

Until the clones are ready both fall back to the GitHub API and then to copies bundled in the
jar — no action needed. `SKILL.md` is the one hard dependency: if it cannot be resolved from
any of the three tiers the step fails rather than generating from a thin prompt.

## 4 — Trigger the Cypress flow

**With Jira credentials** (fetches story live):
```bash
curl -s -X POST http://localhost:8080/api/triggers/manual -H "Content-Type: application/json" -d "{\"flowId\":\"cypress-factory\",\"payload\":{\"issueKey\":\"UIMARCAUTH-553\"}}"
```

The `issueKey`-only form is also the only one that can use **images embedded in the story
description**: the factory fetches the issue itself, asks Jira for the rendered body and the
attachment list, and attaches the pictures to the generation call. The inline form below skips the
Jira fetch entirely, so a story whose requirements live in a screenshot will be generated without
them.

**Without Jira credentials** (inline story):
```bash
curl -s -X POST http://localhost:8080/api/triggers/manual \
  -H "Content-Type: application/json" \
  -d "{\"flowId\":\"cypress-factory\",\"payload\":{\"issueKey\":\"ERM-1001\",\"issue\":{\"fields\":{\"summary\":\"Add agreement name validation\",\"description\":\"AC1: POST /erm/sas with valid name returns 201.\\nAC2: Empty name returns 422.\",\"status\":{\"name\":\"Open\"},\"issuetype\":{\"name\":\"Story\"},\"labels\":[]}}}}"
```

**Run only one part of the flow:**

```bash
# Cases only: stops after TestRail sync (gate 2 opens with "Automation disabled" - approve to finish)
curl -s -X POST localhost:8080/api/triggers/manual -H 'Content-Type: application/json' -d '{"flowId":"cypress-factory","payload":{"issueKey":"UIU-3562","automate":false}}'
# Automation only, on existing TestRail cases (needs FACTORY_DEBUG_ENABLED=true, else 404; "areas" is
# optional and shrinks the explorer prompt, e.g. ["eureka"], ["inventory"])
curl -s -X POST localhost:8080/api/debug/partial-run -H 'Content-Type: application/json' -d '{"flowId":"cypress-factory","startStepId":"automation-select","triggerPayload":{"testCaseIds":["C656309"],"areas":["inventory"]}}'
```

## 5 — Work the gates

Flow: **testcase-gen** → **Gate 1** → testrail-sync → automation-select → automate → **Gate 2** → publish (PR).

Automation takes the cases the sync just created that are marked `Automatable: Yes` (set the flag
at gate 1), up to `FACTORY_CYPRESS_AUTOMATION_MAX_CASES`. For each case, one at a time: the
explorer picks similar specs and helpers, the claude CLI writes the spec, a second CLI session
reviews it (and, with `RUN_SPECS=true`, runs it and fixes it up to twice), and the result is
committed to the run's branch in `factory-app/cypressTafWork/`. Before each commit the TAF's own pre-commit
checks run (`lint-staged`: `prettier --write`, then `stripes-lint --fix`); errors they can't fix are
flagged in the gate 2 report. The first automation run therefore does a one-time `yarn install` in
`factory-app/cypressTafWork/` (several minutes; needs node + yarn on PATH - without them the run continues and
the report says lint was not run).

`testcase-gen` is a single LLM worker that runs MQA's `write-testrail-cases` skill end to end
in one session. Before the generation call it deterministically fetches: the raw Jira issue,
the area context file(s), the golden examples, the TAF fragment library and existing TestRail
coverage. A small routing call decides which area(s) to load and what belongs in `refs` — the
two judgment calls the skill describes. The skill text, examples and context files come from a
local clone of `FACTORY_CYPRESS_FOLIO_AI_REPO` (`factory-app/skillsClone/`), falling back to the GitHub API
and then to copies bundled in the jar.

| Gate | URL | What to check |
|---|---|---|
| Gate 1 — test cases | http://localhost:8080/reviews | `test_plan.md` holds everything in three sections. **`# Analysis`**: which area was detected and why, which context file was loaded, Key Business Rules touched, existing TestRail cases classified as duplicate / update / unrelated, and the AC → scenario coverage matrix — read this first to judge whether the *scope* is right. **`# Test Cases`**: exactly what will be posted to TestRail — check `Test Group` (smoke/critical/extended), `User Journey`, `Automatable` and step quality. **`# Review`**: the automated quality reviewer's verdict and any issues it could not fix in up to 3 internal attempts. The YAML frontmatter carries the sync fields (`refs`, `team`, `ecs_story`, `testrail_section`) — correct `testrail_section` here if the folder is wrong (path string with › separators, or a numeric ID). The originating issue key stays in `refs` even if you edit that field. **Reject and Re-generate**: enter feedback in the "Notes / Re-generation feedback" field to reject and start a new generation immediately, seeded with this plan plus your feedback; a banner and `hitl_feedback.md` appear on the retry run. |
| Gate 2 — autotests | http://localhost:8080/reviews | `automation_report.md`: the run's branch (`factory/<issueKey>`) and the command to see its diff (one commit per case), the changed files, then per case the spec path, the review verdict (`NEEDS_RERUN` / "spec not run" is expected while spec runs are off), findings, and both agents' summaries. Skipped cases are listed with the reason. A failed case's changes are discarded and the loop carries on. **Approve** opens a pull request with the specs in `FACTORY_CYPRESS_TARGET_REPO` (a stripes-testing fork; the terminal prints `[automation] PR opened: <url>`; without the setting, approve just finishes the run); **Reject and Re-generate** re-runs automation for the same cases with your notes, on a fresh branch. |

To refresh the bundled fallback copies of `SKILL.md` and `examples.md`:

```bash
scripts/update-cypress-testcase-skill-bundle.sh /path/to/folio-eureka-ai-dev
```

Watch execution progress: **http://localhost:8080/executions**

## 6 — Inspecting what the model actually received

About half the generation prompt is assembled at runtime — the skill text, the area context
file(s), the raw ticket, and the conditional feedback blocks — so the `/prompts` page shows the
templates, not the rendered result. To capture the real thing, set:

```
FACTORY_DEBUG_ENABLED=true
```

Every model call then writes its fully rendered system + user prompt, **and the model's raw
response appended below it** (under a `# ===== RESPONSE =====` marker in the same file), to
`factory-app/debugPrompts/<executionId>/`. Files are numbered strictly in call order - one number per actual
model call, so the exact numbers shift depending on what happened during the run:

```
1-router.md               area + refs decision
2-coverage-triage.md      which existing TestRail cases got a full read
3-generate-attempt1.md    the big one: SKILL.md + examples + context + ticket
4-review-attempt1.md      what the reviewer agent saw
5-generate-attempt2.md    only when the reviewer failed attempt 1
6-review-attempt2.md
```

One extra label can appear, taking the next sequential number rather than reusing the number of
the call it follows: `generate-attemptN-retry`, when attempt N's response didn't parse as a valid
case block and one corrective retry ran before falling through to the normal reviewer step.

Two runs of the same story are then a plain `diff -r` of their directories, which is usually the
fastest way to answer "why did this one come out worse?" — including, now that responses are
captured, what the reviewer actually said rather than only which issues survived. Nothing
is written when the flag is off, and a dump failure can never fail a run.

## 7 — Checking that prompt caching is working

The generation call caches its system prompt — SKILL.md plus the golden examples, roughly 25k
tokens that never change between calls. The review loop re-sends that prefix up to three times
per run, so the cache should pay for itself within a single run.

Nothing in the test suite can prove it engages (the stubbed model ignores request options), so
check it on a real run. Open **http://localhost:8080/audit**, find the `STEP_COMPLETED` event
for `testcase-gen`, and read its detail:

| What you see | Meaning |
|---|---|
| `cacheWriteTokens` on the first generation call, `cacheReadTokens` on later ones | Working as intended. |
| `cacheReadTokens` on the *first* call too | Even better — a previous run's prefix was still live. |
| `cacheWriteTokens` every call, `cacheReadTokens` never | Not engaging. The system prompt is probably not byte-identical between calls; with `FACTORY_DEBUG_ENABLED=true`, diff the two `generate-attemptN.md` files under `factory-app/debugPrompts/<executionId>/` down to the first `## ` heading (each has its call's prompt above the `# ===== RESPONSE =====` marker and the response below it). |
| Neither count present | The provider is not Anthropic, or the options were dropped — grep the log for `is not supported ... Ignoring.` |

The same counts are exported as `factory.llm.tokens{type=cache_write}` and `{type=cache_read}`.

**Reject and Re-generate is the one case that usually misses.** The retry is a new execution, and
the cache entry lives five minutes from its *last use* — not from when it was created. The
pipeline spends part of that window on the final review call before the gate and on the router
call plus prefetch after it, so you get roughly three to four minutes of actual review time
before the prefix goes cold. A quick "these are wrong, regenerate with X" lands inside it; a
careful read of eight cases will not. If retries miss consistently and you care, the fix is a
one-hour TTL in `TestCaseGenAgentWorker.chatOptions()` — at a higher price per cache write.

## 8 — Time, cost and clean-up

- **Time:**
  - Case generation: about 5-10 minutes up to gate 1.
  - Automation: about 4 minutes per case. The first run also clones the TAF and installs it.
- **Cost:** every run is paid API usage. `FACTORY_CYPRESS_AUTOMATION_MAX_CASES` caps the
  automation part. For prompt work, the automation-only command on one case is the cheapest loop.
- **TestRail:** every generated case carries the `FACTORY_CYPRESS_TESTRAIL_LABEL` label (default
  `cypressFlow-delete`). Delete them with
  `python scripts/cleanup-cypress-testrail-cases.py --dry-run`, then again without the flag.
- **GitHub:** close the test PRs on your fork. Local run branches live in
  `factory-app/cypressTafWork`. Each run starts from fresh `origin/master`, so old branches
  don't interfere.
- **Executions:** `scripts/reset-executions.sh` deletes stuck (non-terminal) executions; add `--all` to delete every execution. The audit log is kept.
