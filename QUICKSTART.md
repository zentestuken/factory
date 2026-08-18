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
docker compose up -d
```

Verify it's healthy:
```bash
docker compose ps
# should show "healthy" next to the postgres container
```

---

## Step 2 — Authenticate with Anthropic

**Option A — CodeMie SSO (no personal API key needed)**

Start `codemie-claude` before launching the factory. The application detects the
running CodeMie proxy port automatically at startup and routes all LLM calls
through it. Nothing else to configure.

If auto-detection fails (e.g. CodeMie isn't running yet), you'll see no
`[factory] CodeMie proxy - routing Anthropic API to http://localhost:<port>` line in the startup log — start CodeMie and
restart the factory.

**Option B — Direct Anthropic API key**

Set `ANTHROPIC_API_KEY` in your `.env` file and `source .env` in Git Bash before running.

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
# LLM — Option A: CodeMie SSO (start codemie-claude first, no key needed)
# LLM — Option B: direct Anthropic key
ANTHROPIC_API_KEY=sk-ant-api03-...

# Jira (optional — skip to use inline issue payload instead)
FACTORY_CONNECTORS_JIRA_BASE_URL=https://issues.folio.org
FACTORY_CONNECTORS_JIRA_EMAIL=your@email.com
FACTORY_CONNECTORS_JIRA_API_TOKEN=your-token

# GitHub (required for context file fetch, examples fetch, and TAF clone)
FACTORY_CONNECTORS_GITHUB_TOKEN=ghp_...
# Repo containing FOLIO AI skills (context files + examples.md fetched at runtime)
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
# FACTORY_CYPRESS_TESTRAIL_CASE_TYPE_ID=1      # 1=Functional on foliotest
# FACTORY_CYPRESS_TESTRAIL_PRIORITY_ID=3       # 3=Medium (default)
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

On first startup, `TafSyncService` clones `folio-org/stripes-testing` into `cypressTafClone/` in the background (shallow, ~30 s). Fragment scan uses the GitHub API until the clone is ready — no action needed.

### 4 — Trigger the Cypress flow

**With Jira credentials** (fetches story live):
```bash
curl -s -X POST http://localhost:8080/api/triggers/manual \
  -H "Content-Type: application/json" \
  -d "{\"flowId\":\"cypress-factory\",\"payload\":{\"issueKey\":\"UIIN-2908\"}}"
```

**Without Jira credentials** (inline story):
```bash
curl -s -X POST http://localhost:8080/api/triggers/manual \
  -H "Content-Type: application/json" \
  -d "{\"flowId\":\"cypress-factory\",\"payload\":{\"issueKey\":\"ERM-1001\",\"issue\":{\"fields\":{\"summary\":\"Add agreement name validation\",\"description\":\"AC1: POST /erm/sas with valid name returns 201.\\nAC2: Empty name returns 422.\",\"status\":{\"name\":\"Open\"},\"issuetype\":{\"name\":\"Story\"},\"labels\":[]}}}}"
```

### 5 — Work the gates

Flow: triage → context-load → fragment-scan → examples-load → **coverage-load** → scenario-analysis → **Gate 0** → test-spec → **Gate 1** → testrail-sync

`coverage-load` reads the detected area from `context.md`, keyword-matches it against all TestRail sections, and loads cases only from area-specific sections. The placement-fallback section (`FACTORY_CYPRESS_TESTRAIL_SECTION_ID`) is never scanned for coverage — it is non-thematic and excluded by design. If no area-specific section is found, `existing_coverage.md` is empty (safe for the LLM).

| Gate | URL | What to check |
|---|---|---|
| Gate 0 — scenario coverage | http://localhost:8080/reviews | `scenario_list.md`: S-NN proposals with shape notes and coverage matrix (AC → Scenario → NEW/EXISTING/SKIPPED); confirm/adjust scope; remove EXISTING rows unless the story changes that behaviour; edit `testrail_section` frontmatter field if the suggested TestRail folder is wrong; `existing_coverage.md` shows what's already in TestRail for this section |
| Gate 1 — test cases | http://localhost:8080/reviews | `test_plan.md`: one case per confirmed scenario; check `testGroup` (smoke/critical/extended), `userJourney` flag, `automatable` flag, and step quality; `testcase_review.md`: automated quality review result — shows issues the reviewer found and whether they were auto-fixed across up to 3 internal attempts; `scenario_list.md` is also reviewable — amend `testrail_section` here for a final override before sync. **Reject and Re-generate**: enter feedback in the "Notes / Re-generation feedback" field and click the button to reject current cases and start a new generation immediately, preserving all Gate 0 decisions (scenario list, context, coverage). On retry runs a banner is shown and `hitl_feedback.md` + `previous_test_plan.md` appear as read-only artifacts. |

Watch execution progress: **http://localhost:8080/executions**
