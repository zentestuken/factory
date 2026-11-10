# Factory Test Run — Step by Step

A complete local run using the built-in sample story. No Jira, TestRail, or GitHub credentials needed.

---

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| Java | 21+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker | any recent | `docker info` |
| Anthropic API key **or** CodeMie SSO | — | see Step 2 |

---

## Step 1 — Start the database

```bash
docker compose up -d postgres
```

Name the service: plain `docker compose up -d` now also builds and starts a `factory` app container
(and a coding-runtime image for the Developer Flow), which takes port 8080 and polls the same
database as the app you start in Step 4.

Verify it's healthy:
```bash
docker compose ps
# should show "healthy" next to the postgres container
```

---

## Step 2 — Authenticate with Anthropic

**Option A — CodeMie SSO (EPAM corporate — no personal API key needed)**

Start the CodeMie desktop app or CLI proxy. The factory auto-detects the running Node.js
proxy process via PowerShell and routes all Anthropic API calls through it — no env vars needed.

Verification: look for this line in the startup log:

```
[factory] CodeMie proxy - routing Anthropic API to http://localhost:XXXXX
```

If you see `[factory] CodeMie proxy not found` instead, start the CodeMie proxy and restart the factory.

> **Override auto-detection:** Set `ANTHROPIC_BASE_URL=http://localhost:<port>` to point at a
> specific proxy URL instead of relying on auto-detection.

> **Pitfall:** `ANTHROPIC_API_KEY` bypasses CodeMie entirely. If that variable is set in your
> shell, the factory uses it directly — even if you commented it out of `.env`. To remove it:
> `unset ANTHROPIC_API_KEY`.

**Option B — Direct Anthropic API key**

Set `ANTHROPIC_API_KEY` in your `.env` file and `source .env` in Git Bash before running.

> **Pitfall:** `source .env` sets variables but never _unsets_ them. If you previously
> sourced the file with `ANTHROPIC_API_KEY` set and then comment it out, the key stays
> live in your current shell. To actually remove it run `unset ANTHROPIC_API_KEY` (and any
> other variables you want gone) before restarting the factory.

---

## Step 3 — Build

```bash
mvn install -DskipTests
```

---

## Step 4 — Start the application

```bash
mvn spring-boot:run -pl factory-app
```

Wait for:
```
Started FactoryApplication in X seconds
```

Leave this terminal open.

---

## Step 5 — Open the UI

Go to **http://localhost:8080/reviews** — this is your review inbox. It will be empty for now.

---

## Step 6 — Trigger the pipeline

In a new terminal (Git Bash or cmd):

```bash
curl -s -X POST http://localhost:8080/api/triggers/manual -H "Content-Type: application/json" -d "{\"flowId\":\"test-factory\",\"payload\":{\"issueKey\":\"ERM-1001\",\"issue\":{\"key\":\"ERM-1001\",\"fields\":{\"summary\":\"Validate agreement names on creation\",\"description\":\"As a librarian I want agreement names to be validated when creating a subscription agreement so that invalid records cannot be saved.\n\nAC1: POST /erm/sas with a valid name (1-255 chars) creates the agreement and returns 201.\nAC2: POST /erm/sas with a missing or empty name returns 422 with a field error.\nAC3: Names longer than 255 characters are rejected with 422.\",\"status\":{\"name\":\"Ready for QA\"},\"issuetype\":{\"name\":\"Story\"},\"labels\":[\"ai-factory\"]}}}}"
```

You'll get back an execution ID:
```json
{ "executionId": "..." }
```

---

## Step 7 — Watch progress

```bash
# execution list
curl -s http://localhost:8080/api/executions | jq .[0].status
```

Or watch in the browser: **http://localhost:8080/executions**

The pipeline runs two AI steps (Triage → Test Specification) and then pauses. After roughly 20–40 seconds you'll see a review appear in the inbox.

---

## Step 8 — Gate 1: Review the test plan

Go to **http://localhost:8080/reviews** and click the pending review.

You'll see:
- `scope_manifest.md` — what the AI understood about the story (endpoints, risk, ambiguities)
- `test_plan.md` — the generated test cases (positive, edge, negative)

**Options:**
- **Approve** — happy with the test plan, pipeline continues
- **Amend** — edit any artifact text area in the browser, then submit; your version becomes v2
- **Reject** — cancels the run

For a test run, just click **Approve**.

---

## Step 9 — Wait for Gate 2

After approval the pipeline runs two more AI steps (Test Automation → Test Execution). Another review will appear in the inbox in ~20–40 seconds.

---

## Step 10 — Gate 2: Sign off on generated tests

Open the second review. You'll see:
- `test_scripts.md` — generated Karate `.feature` files
- `test_results.md` — advisory results (all cases `NOT_EXECUTED` because no test environment is configured — this is expected)

Click **Approve**.

---

## Step 11 — Done

The finalizer runs and the execution completes. Open the execution detail to see the full timeline:

**http://localhost:8080/executions** → click the row

You'll see every step, every artifact (with version history if you amended anything), your review decisions, and a `sync_report.md` at the end showing all connectors were skipped — which is expected with no credentials configured.

---

## What you should see in the sync report

```
- github: commit scripts: skipped — no target repository configured (FACTORY_FLOWA_TARGET_REPO)
- testrail: sync cases: skipped — no TestRail section configured (FACTORY_FLOWA_TESTRAIL_SECTION_ID)
- jira: comment: skipped — Jira connector not configured
```

All skipped — correct. The artifacts (test plan, Karate scripts) were still generated and are visible in the execution timeline.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Pipeline stays `PENDING` for >1 minute | No API key / CodeMie not running — check startup log for `[factory] CodeMie proxy` line; if absent, start `codemie-claude` and restart the factory |
| `Started FactoryApplication` never appears | Port 5432 not available — check `docker compose ps` |
| No review appears after trigger | LLM call failed — check app logs for `ERROR` |
| Review page is blank | Browser cached a redirect — hard-refresh |

---

## Testing the Cypress Flow

All commands needed after each update cycle, in order. Commands/syntax provided are for Bash shell.

### 1 — Rebuild

```bash
mvn install -DskipTests
```

### 2 — Load env vars

Create a `.env` file in the repo root (gitignored) with your credentials:

```
# LLM — Option A: CodeMie SSO (EPAM)
# Start the CodeMie desktop app or CLI proxy — no env vars needed.
# The factory auto-detects the running proxy process at startup.
# ANTHROPIC_API_KEY must NOT be set (it bypasses CodeMie entirely).
# Optional explicit override: ANTHROPIC_BASE_URL=http://localhost:<port>

# LLM — Option B: direct Anthropic key
ANTHROPIC_API_KEY=sk-ant-api03-...

# Jira (optional — skip to use inline issue payload instead)
FACTORY_CONNECTORS_JIRA_BASE_URL=https://issues.folio.org
FACTORY_CONNECTORS_JIRA_EMAIL=your@email.com
FACTORY_CONNECTORS_JIRA_API_TOKEN=your-token

# GitHub — both clones are public and need no token. The token is used for the API
# fallback tiers and for context enrichment (package.json / en_US.json from ui-* repos).
FACTORY_CONNECTORS_GITHUB_TOKEN=ghp_...
# Repo containing FOLIO AI skills — SKILL.md, examples.md and the 38 area context files.
# Cloned to skillsClone/ at startup; GitHub API and the bundled jar copies are the fallbacks.
FACTORY_CYPRESS_FOLIO_AI_REPO=folio-org/folio-eureka-ai-dev
# Repo where generated Cypress specs will be committed (Phase 3+)
# FACTORY_CYPRESS_TARGET_REPO=folio-org/stripes-testing

# TestRail (optional — flow runs in advisory mode without these)
# FACTORY_CONNECTORS_TESTRAIL_BASE_URL=https://foliotest.testrail.io
# FACTORY_CONNECTORS_TESTRAIL_USERNAME=your@email.com
# FACTORY_CONNECTORS_TESTRAIL_API_KEY=your-key
# FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID=14
# FACTORY_CONNECTORS_TESTRAIL_SUITE_ID=        # only for multi-suite projects
# Default section fallback (used when LLM suggestion is absent or unresolvable)
# FACTORY_CYPRESS_TESTRAIL_SECTION_ID=114774
# Case type and priority are resolved BY NAME against TestRail own get_case_types at sync
# time - ids drift whenever TestRail is re-templated, so they are not configured as ids.
# FACTORY_CYPRESS_TESTRAIL_CASE_TYPE_NAME=Functional
# FACTORY_CYPRESS_TESTRAIL_PRIORITY_NAME=Medium
# FACTORY_CYPRESS_TESTRAIL_LABEL=cypressFlow-delete
# Max agent review iterations per run (default 3; 0 skips automated review entirely)
# FACTORY_CYPRESS_MAX_REVIEW_ITERATIONS=3
# Jira projects never written into TestRail refs (default FAT)
# FACTORY_CYPRESS_REFS_EXCLUDED_PROJECTS=FAT
# Send images embedded inline in the story description to the model (default true). Set false
# if FACTORY_LLM_MODEL points at a model with no image support - such a model REJECTS a call
# carrying images rather than ignoring them.
# FACTORY_CYPRESS_SEND_IMAGES=true
```

Then source it in Git Bash:

```bash
set -a; source .env; set +a
```

`set -a` auto-exports every variable sourced so child processes (`mvn`, `curl`) inherit them.

### 3 — Start the factory

```bash
mvn spring-boot:run -pl factory-app
```

On first startup two background clones run (shallow, ~30 s each, non-blocking):

- `TafSyncService` → `folio-org/stripes-testing` into `cypressTafClone/` (Cypress fragments)
- `SkillWorkspaceService` → `FACTORY_CYPRESS_FOLIO_AI_REPO` into `skillsClone/` (SKILL.md,
  `examples.md`, the 38 area context files)

Until the clones are ready both fall back to the GitHub API and then to copies bundled in the
jar — no action needed. `SKILL.md` is the one hard dependency: if it cannot be resolved from
any of the three tiers the step fails rather than generating from a thin prompt.

### 4 — Trigger the Cypress flow

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

### 5 — Work the gates

Flow: **testcase-gen** → **Gate 1** → testrail-sync — three steps, two artifacts.

`testcase-gen` is a single LLM worker that runs MQA's `write-testrail-cases` skill end to end
in one session. Before the generation call it deterministically fetches: the raw Jira issue,
the area context file(s), the golden examples, the TAF fragment library and existing TestRail
coverage. A small routing call decides which area(s) to load and what belongs in `refs` — the
two judgment calls the skill describes. The skill text, examples and context files come from a
local clone of `FACTORY_CYPRESS_FOLIO_AI_REPO` (`skillsClone/`), falling back to the GitHub API
and then to copies bundled in the jar.

| Gate | URL | What to check |
|---|---|---|
| Gate 1 — test cases | http://localhost:8080/reviews | `test_plan.md` holds everything in three sections. **`# Analysis`**: which area was detected and why, which context file was loaded, Key Business Rules touched, existing TestRail cases classified as duplicate / update / unrelated, and the AC → scenario coverage matrix — read this first to judge whether the *scope* is right. **`# Test Cases`**: exactly what will be posted to TestRail — check `Test Group` (smoke/critical/extended), `User Journey`, `Automatable` and step quality. **`# Review`**: the automated quality reviewer's verdict and any issues it could not fix in up to 3 internal attempts. The YAML frontmatter carries the sync fields (`refs`, `team`, `ecs_story`, `testrail_section`) — correct `testrail_section` here if the folder is wrong (path string with › separators, or a numeric ID). The originating issue key stays in `refs` even if you edit that field. **Reject and Re-generate**: enter feedback in the "Notes / Re-generation feedback" field to reject and start a new generation immediately, seeded with this plan plus your feedback; a banner and `hitl_feedback.md` appear on the retry run. |

To refresh the bundled fallback copies of `SKILL.md` and `examples.md`:

```bash
scripts/update-cypress-testcase-skill-bundle.sh /path/to/folio-eureka-ai-dev
```

Watch execution progress: **http://localhost:8080/executions**

### 6 — Inspecting what the model actually received

About half the generation prompt is assembled at runtime — the skill text, the area context
file(s), the raw ticket, and the conditional feedback blocks — so the `/prompts` page shows the
templates, not the rendered result. To capture the real thing, set:

```
FACTORY_DEBUG_ENABLED=true
```

Every model call then writes its fully rendered system + user prompt, **and the model's raw
response appended below it** (under a `# ===== RESPONSE =====` marker in the same file), to
`debugPrompts/<executionId>/`. Files are numbered strictly in call order - one number per actual
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

### 7 — Checking that prompt caching is working

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
| `cacheWriteTokens` every call, `cacheReadTokens` never | Not engaging. The system prompt is probably not byte-identical between calls; with `FACTORY_DEBUG_ENABLED=true`, diff the two `generate-attemptN.md` files under `debugPrompts/<executionId>/` down to the first `## ` heading (each has its call's prompt above the `# ===== RESPONSE =====` marker and the response below it). |
| Neither count present | The provider is not Anthropic, or the options were dropped — grep the log for `is not supported ... Ignoring.` |

The same counts are exported as `factory.llm.tokens{type=cache_write}` and `{type=cache_read}`.

**Reject and Re-generate is the one case that usually misses.** The retry is a new execution, and
the cache entry lives five minutes from its *last use* — not from when it was created. The
pipeline spends part of that window on the final review call before the gate and on the router
call plus prefetch after it, so you get roughly three to four minutes of actual review time
before the prefix goes cold. A quick "these are wrong, regenerate with X" lands inside it; a
careful read of eight cases will not. If retries miss consistently and you care, the fix is a
one-hour TTL in `TestCaseGenAgentWorker.chatOptions()` — at a higher price per cache write.
