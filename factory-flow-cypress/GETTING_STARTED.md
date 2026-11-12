# Cypress Factory — getting started

One flow, `cypress-factory`, takes a Jira story to Cypress specs in a PR:

```
Jira story ─► test cases ─► GATE 1 (QA) ─► TestRail ─► specs, one per case ─► GATE 2 (AQA) ─► PR on your fork
             (LLM, MQA skill)                         (claude CLI: your explorer /
                                                        automate / review prompts)
```

Nothing goes to TestRail or GitHub without a human approving a gate. Every command below runs from
the **repository root**. More detail is in [QUICKSTART.md](QUICKSTART.md); design and history
are in [CYPRESS_FLOW_PLAN.md](CYPRESS_FLOW_PLAN.md).

---

## 1. What you need

- [ ] **Java 21 + Maven**
- [ ] **Docker or Podman**, for Postgres
- [ ] **`claude` CLI** on PATH (`claude --version`). The automation steps run it.
- [ ] **node + yarn** on PATH. The first automation run installs the TAF's `node_modules` once, and
      its lint-staged checks run before each commit.
- [ ] **Chrome**, and a decent connection to the `folio-etesting-snapshot` environments. The review
      step runs each spec there.
- [ ] **Credentials:**
  - an Anthropic API key;
  - Jira (to fetch the story);
  - TestRail (cases are created there, and automation reads them back);
  - a GitHub token with **push access to your own fork** of `folio-org/stripes-testing`.
- [ ] **Your fork** of `stripes-testing`. PRs go there, never to upstream.

## 2. One-time setup

```bash
cp .env.example .env
```

Set these in `.env`. Everything else has a working default.

```bash
ANTHROPIC_API_KEY=...                          # also used by the claude CLI - automation needs it
FACTORY_CONNECTORS_JIRA_BASE_URL=https://folio-org.atlassian.net
FACTORY_CONNECTORS_JIRA_EMAIL=you@example.com
FACTORY_CONNECTORS_JIRA_API_TOKEN=...
FACTORY_CONNECTORS_TESTRAIL_BASE_URL=https://foliotest.testrail.io
FACTORY_CONNECTORS_TESTRAIL_USERNAME=you@example.com
FACTORY_CONNECTORS_TESTRAIL_API_KEY=...
FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID=14
FACTORY_CONNECTORS_TESTRAIL_SUITE_ID=21
FACTORY_CONNECTORS_GITHUB_TOKEN=...
FACTORY_CYPRESS_TARGET_REPO=<you>/stripes-testing   # your fork; unset = no PR
FACTORY_CYPRESS_AUTOMATION_MAX_CASES=2         # cost cap: cases automated per run
FACTORY_CYPRESS_AUTOMATION_RUN_SPECS=true      # the review step runs each spec and fixes it
FACTORY_DEBUG_ENABLED=true                     # needed for "automation only" runs + prompt dumps
```

**Spec runs are implemented but have not been verified end to end yet. You'll be the first to
run them.** Each case runs on the environment that matches its TestRail **ECS Enabled** flag:
- non-ECS: etesting snapshot, tenant `diku`;
- ECS: etesting snapshot, tenant `consortium`.

Before each review the factory writes them into the TAF's gitignored `environments.js`. To use
other environments, point `FACTORY_CYPRESS_AUTOMATION_RUN_ENVS` at a JSON file shaped like
`factory-flow-test-automation/src/main/resources/cypress-run-environments.json`. With `false`,
specs are reviewed but not run, and the verdict is `NEEDS_RERUN`.

## 3. Start

```bash
docker compose up -d postgres                  # Podman: set DOCKER_HOST to its socket/pipe first
mvn install -DskipTests                        # after every code or prompt change
set -a; source .env; set +a
mvn spring-boot:run -pl factory-app
```

The console is at http://localhost:8080. The pages you'll use are **/reviews** (the gates) and
**/executions** (progress).

## 4. Run it — three ways

```bash
# Full flow: story -> cases -> gate 1 -> TestRail -> specs -> gate 2 -> PR
curl -s -X POST localhost:8080/api/triggers/manual -H 'Content-Type: application/json' \
  -d '{"flowId":"cypress-factory","payload":{"issueKey":"UIOR-1492"}}'

# Automation only, on cases already in TestRail (your main loop when iterating on prompts)
curl -s -X POST localhost:8080/api/debug/partial-run -H 'Content-Type: application/json' \
  -d '{"flowId":"cypress-factory","startStepId":"automation-select","triggerPayload":{"testCaseIds":["C1621363"],"areas":["orders"]}}'

# Test cases only, no automation
curl -s -X POST localhost:8080/api/triggers/manual -H 'Content-Type: application/json' \
  -d '{"flowId":"cypress-factory","payload":{"issueKey":"UIOR-1492","automate":false}}'
```

`areas` is optional. It narrows the explorer's file list to that part of the TAF, for example
`eureka`, `orders` or `inventory`.

**Watch the terminal.** Every gate prints a line like
`[gate] Waiting for review: "…" - open /reviews/<id>`. Automation prints
`[automation] Automating N of M case(s)…` and `[automation] Finished: …`.

## 5. The two gates

**Gate 1 - test cases** (`test_plan.md`, written by the MQA skill).
- Check that the scope and the cases make sense.
- Set `Automatable: Yes` only on the cases you want automated. The cap takes the first N; the rest
  are listed as skipped.
- Approve to create the cases in TestRail.

**Gate 2 - autotests** (`automation_report.md`). Per case it shows:
- the spec path and the environment it ran on;
- the review verdict (`PASS` / `NEEDS_CHANGES` / `FAIL`) and the findings;
- the lint result;
- both CLI sessions' summaries.

A ⚠ line at the top flags any lint errors left. A spec is committed whatever its verdict, so a
`FAIL` is still there for you to look at. Only a crashed or timed-out case is discarded.
- To see the code: `git -C factory-app/cypressTafWork log -p origin/master..HEAD`. There is one
  commit per case, on branch `factory/<issueKey>`.
- **Approve:** the factory pushes the branch to your fork and opens a PR. The terminal prints
  the PR URL.
- **Reject and Re-generate:** reruns automation for the same cases with your note. It currently
  starts from scratch: the model doesn't see its previous specs. So make the note
  self-contained, e.g. "create templates via `POST templates` with `scope: 'orders-email'`".

## 6. What happens per case

1. **Explore:** your explorer prompt picks similar specs and helpers.
2. **Write the spec:** your automate prompt runs in the claude CLI.
3. **Review:** your review prompt runs in the claude CLI. With `RUN_SPECS=true` it also runs the
   spec and fixes it, at most twice.
4. **Format and lint:** `lint-staged`, exactly what the TAF's pre-commit hook runs.
5. **Commit.**

A case that fails is reported, its changes are discarded, and the next case continues.

Each case is told its TestRail dev team (for the tag), the author's automation notes from gate 1,
and the gate 2 note if any.

**Where things live** (the app runs from `factory-app/`):

| What | Where |
|---|---|
| Work clone with the run branches | `factory-app/cypressTafWork` (yours; `cypressTafClone` is read-only for the case flow) |
| Your prompts | `factory-flow-test-automation/src/main/resources/prompts/` |
| Every rendered prompt + response of a run | `factory-app/debugPrompts/<executionId>/` |

## 7. Iterating on your prompts

Edit your prompts, run `mvn install -DskipTests`, restart the app, then use the **automation
only** command above on one existing case. That costs one case, a few minutes, and no case
generation.

## 8. Time, cost, clean-up

- **Time:** case generation takes about 5-10 min. Automation takes about 4 min per case without
  spec runs; with spec runs it takes longer, depending on the spec and the environment (each CLI
  session is capped at 30 min). The very first automation run adds the clone and `yarn install`.
- **Cost:** every run is paid API usage. The cap (`MAX_CASES`) limits the automation part.
- **Clean-up:**
  - Generated TestRail cases carry the label `cypressFlow-delete`; remove them with
    `python scripts/cleanup-cypress-testrail-cases.py` (`--dry-run` first).
  - Close test PRs on your fork.

## 9. If something goes wrong

| Symptom | Cause |
|---|---|
| Automation fails at once, `claude` errors | `claude` not on PATH or not authenticated. It uses `ANTHROPIC_API_KEY` when set, otherwise its own login; check `claude -p hi` in the shell that starts the app. |
| Report says "Lint: not run" | node / yarn missing; the specs are still committed |
| `FAIL` on page-load timeouts or login errors | Usually the environment, not the spec (Cypress `pageLoadTimeout` is 60 s). Run the spec yourself in `factory-app/cypressTafWork` to tell which. Please note such cases: environment failures aren't told apart from spec defects yet. |
| Gate 2 "No automatable cases" / "Automation disabled" | Every case was `Automatable: No`, or the run had `"automate": false`. Approve to finish. |
| No PR after approving | `FACTORY_CYPRESS_TARGET_REPO` unset, or the token can't push to your fork. The terminal says which. |
| Automation-only call returns 404 | `FACTORY_DEBUG_ENABLED=true` not set |
