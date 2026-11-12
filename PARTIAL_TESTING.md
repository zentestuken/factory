# Partial Flow Testing Guide

How to run the cypress-factory flow starting from a specific step, without re-running all earlier steps every time.

---

## Problem

Running the whole cypress-factory flow to test a change in a late step wastes time and money: case
generation alone is several minutes and several LLM calls. Partial-run starts an execution at any
step, with the artifacts that step needs seeded in the request.

---

## Flow Step Reference

`cypress-factory` (2026-11-12). Start a partial run by **`startStepId`** - indices shift whenever a
step is added; `startStepIndex` still works.

| # | step_id | type | inputs | outputs |
|---|---------|------|--------|---------|
| 0 | `testcase-gen` | AGENT | `$trigger` (optional: `test_plan.md`, `hitl_feedback.md`) | `test_plan.md` |
| 1 | `gate-1-review` | HITL_GATE | — | — |
| 2 | `testrail-sync` | AGENT | `test_plan.md` | `test_plan.md` _(real C###### ids)_, `testrail_cases.json` |
| 3 | `automation-select` | AGENT | `$trigger` (optional: `testrail_cases.json`) | `automation_cases.json` |
| 4 | `automate` | AGENT | `automation_cases.json` (optional: `hitl_feedback.md`) | `automation_report.md`, `automation_results.json` |
| 5 | `gate-2-review` | HITL_GATE | — | — |

Running specs and fixing them happens inside `automate` (Ostap's review session, when
`FACTORY_CYPRESS_AUTOMATION_RUN_SPECS=true`); there is no separate execution or failure-analysis
step. A PR step after gate 2 is planned (`factory-flow-cypress/CYPRESS_AUTOMATION_MERGE_PLAN.md`).

**Most common starting points:**

| startStepId | What you're testing | Seed |
|---|---|---|
| `gate-1-review` | Gate amendment flow, gate → testrail-sync path | artifact `test_plan.md` |
| `testrail-sync` | TestRail section/label/team wiring, the `testrail_cases.json` hand-off | artifact `test_plan.md` |
| `automation-select` ⭐ | Automation on existing TestRail cases | **no artifacts** - `triggerPayload: {"testCaseIds": ["C…"], "areas": ["eureka"]}` (`areas` optional, narrows the explorer prompt) |
| `automate` | The per-case loop with a hand-made selection | artifact `automation_cases.json` (copy one from a prior run) |

> ⭐ `automation-select` is the usual way to iterate on the automation prompts: it fetches the
> cases from TestRail itself, so the only thing to provide is their ids.

---

## How to Get Fixture Artifacts from a Prior Run

The cleanest source of fixture content is a real prior execution. After any successful run:

```bash
# List recent executions
curl -s http://localhost:8080/api/executions | jq '[.[].id, .[].status]'

# Get all artifacts for a specific execution
curl -s http://localhost:8080/api/executions/{executionId} | jq '.artifacts'
```

Each artifact in the response has a `content` field — that is your fixture text. Copy it per artifact name.

Alternatively, use the execution detail page in the browser: **http://localhost:8080/executions** → click a row → each artifact is shown with its content.

Save the content to local files (e.g. `fixtures/scope_manifest.md`, `fixtures/context.md`, etc.) for reuse across test iterations.

---

## Debug Trigger API Endpoint (currently implemented approach)

### How It Works

A new endpoint `POST /api/debug/partial-run` (active only when `FACTORY_DEBUG_ENABLED=true`) creates an execution with a non-zero starting step and pre-seeded artifacts in a single atomic DB transaction. The poller picks it up within 2 seconds and starts the engine at the specified step — which sees all required artifacts already present.

**Thread safety:** `@Transactional` on the handler means execution creation, step cursor mutation, and all artifact inserts commit together. The poller's claim query runs in a separate transaction and cannot see the new row until the handler's transaction commits.

**Why the engine handles a non-zero starting step correctly:** `ExecutionEngine.advance()` reads `currentStepIndex` from the DB row and calls `flow.step(index)` directly. There is no "must start at 0" guard in the engine loop — the constraint is only in `PipelineExecution`'s constructor. The debug endpoint bypasses the constructor's initial value by calling `setCurrentStepIndex()` before the first commit.

### What Is Built

**File 1:** `factory-app/src/main/java/org/folio/factory/app/debug/DebugPartialRunController.java`

```java
@RestController
@RequestMapping("/api/debug")
@ConditionalOnProperty(name = "factory.debug.enabled", havingValue = "true")
public class DebugPartialRunController {

    private final StateManager stateManager;
    private final PipelineExecutionRepository executionRepository;
    private final ArtifactStore artifactStore;
    private final FlowRegistry flowRegistry;
    private final JsonMapper jsonMapper;

    // constructor injection of all five

    public record PartialRunRequest(
        String flowId,
        int startStepIndex,
        JsonNode triggerPayload,
        Map<String, String> artifacts
    ) {}

    public record PartialRunResponse(
        UUID executionId,
        String flowId,
        int startStepIndex,
        List<String> seededArtifacts
    ) {}

    @PostMapping("/partial-run")
    @Transactional
    public ResponseEntity<PartialRunResponse> start(@RequestBody PartialRunRequest req) {
        // 1. Validate flow and step index
        FlowDescriptor flow = flowRegistry.require(req.flowId());
        if (!flow.hasStep(req.startStepIndex())) {
            throw new IllegalArgumentException("startStepIndex " + req.startStepIndex()
                + " out of range for flow '" + req.flowId() + "' ("
                + flow.agentChain().size() + " steps)");
        }
        // 2. Validate all inputs for the start step are provided
        StepDescriptor startStep = flow.step(req.startStepIndex());
        for (String inputName : startStep.inputs()) {
            if (!"$trigger".equals(inputName)
                    && (req.artifacts() == null || !req.artifacts().containsKey(inputName))) {
                throw new IllegalArgumentException(
                    "Missing artifact '" + inputName + "' required by step '"
                    + startStep.stepId() + "'");
            }
        }
        // 3. Create execution (lands at currentStepIndex=0, status=PENDING,
        //    but is not yet committed — same transaction)
        String payloadJson = req.triggerPayload() == null ? null
            : jsonMapper.writeValueAsString(req.triggerPayload());
        PipelineExecution execution = stateManager.createExecution(
            req.flowId(), flow.version(), payloadJson, null);

        // 4. Advance cursor to target step (still within same transaction)
        execution.setCurrentStepIndex(req.startStepIndex());
        executionRepository.save(execution);

        // 5. Seed artifacts
        List<String> seeded = new ArrayList<>();
        if (req.artifacts() != null) {
            for (var entry : req.artifacts().entrySet()) {
                artifactStore.putMarkdown(execution.getId(),
                    entry.getKey(), entry.getValue(), "debug:seed");
                seeded.add(entry.getKey());
            }
        }
        // 6. Commit — poller sees row + cursor + artifacts atomically
        return ResponseEntity.accepted()
            .body(new PartialRunResponse(execution.getId(),
                req.flowId(), req.startStepIndex(), seeded));
    }
}
```

**File 2:** `application.yaml` addition:
```yaml
factory:
  debug:
    enabled: ${FACTORY_DEBUG_ENABLED:false}
```

No factory-core changes needed. `StateManager`, `ArtifactStore`, `FlowRegistry`, and `PipelineExecutionRepository` are all accessible from factory-app (the composition root).

### Usage

1. Set `FACTORY_DEBUG_ENABLED=true` and restart (only needed once per session — no YAML editing, no rebuild for each test iteration)

2. Call the endpoint with fixture content:
   ```bash
   curl -s -X POST http://localhost:8080/api/debug/partial-run \
     -H "Content-Type: application/json" \
     -d '{
       "flowId": "cypress-factory",
       "startStepIndex": 2,
       "triggerPayload": {"issueKey": "ERM-1234", "automate": false},
       "artifacts": {
         "test_plan.md": "---\nissue_key: ERM-1234\nteam: spitfire\necs_story: \"non-ecs\"\ntestrail_section: 114774\ncase_count: 1\n---\n\n## TC-1: Create purchase order\n\n..."
       }
     }'
   ```
   Response: `{"executionId":"<uuid>","flowId":"cypress-factory","startStepIndex":2,"seededArtifacts":[...]}`

3. Within 2 seconds, `testrail-sync` begins running. Monitor at `http://localhost:8080/executions`.

4. For the next test iteration — no rebuild, no restart. Just call the endpoint again with updated artifact content (or same content if only the worker/prompt code changed).

**Workflow for iterating on testcase-gen prompt changes:**
```
edit system.md / user.md under prompts/cypress-testcase-gen/
  → mvn install -DskipTests
  → (app still running — hot reload not needed, jars updated in ~/.m2)
  → restart app
  → POST /api/triggers/manual OR /api/debug/partial-run (startStepIndex: 0)
  → review gate-1 output
  → repeat
```

**To start from step 2 (testrail-sync only):**

`"automate": false` keeps the run from going on into the (paid) automation steps after the sync;
gate 2 then opens with "Automation disabled" - approve it to finish.

Replace `114774` with your real TestRail section ID, then copy and paste as-is:

```bash
curl -s -X POST http://localhost:8080/api/debug/partial-run \
  -H "Content-Type: application/json" \
  --data-binary @- <<'EOF'
{
  "flowId": "cypress-factory",
  "startStepIndex": 2,
  "triggerPayload": {"issueKey": "ERM-1234", "automate": false},
  "artifacts": {
    "test_plan.md": "---\nissue_key: ERM-1234\nteam: spitfire\necs_story: \"non-ecs\"\ntestrail_section: 114774\ncase_count: 1\n---\n\n## TC-1: Create purchase order\n\n- **Tier:** criticalPath\n- **Automatable:** Yes\n- **ECS:** No\n\n**Preconditions**\n- User with Acquisitions module access is logged in\n- At least one active vendor record exists in the system\n\n**Steps**\n1. **Action:** Navigate to the Orders module and open Purchase orders\n   **Expected:** Purchase orders list is displayed with New button visible\n2. **Action:** Click New to open the order creation form\n   **Expected:** Order creation form opens with all required fields empty\n3. **Action:** Fill in Vendor and Order type then click Save and close\n   **Expected:** Order is saved with status Pending and appears in the list\n"
  }
}
EOF
```

**To test gate-1 amendment → testrail-sync (start from step 1):**

Same section ID substitution. Gate-1 opens immediately — go to `/reviews`, edit the `## TC-1:` heading in the textarea to a different title (e.g. `## TC-1: Create purchase order — revised`), click **Amend**. Testrail-sync runs automatically after approval and the TestRail case should carry the amended heading title.

```bash
curl -s -X POST http://localhost:8080/api/debug/partial-run \
  -H "Content-Type: application/json" \
  --data-binary @- <<'EOF'
{
  "flowId": "cypress-factory",
  "startStepIndex": 1,
  "triggerPayload": {"issueKey": "ERM-1234", "automate": false},
  "artifacts": {
    "test_plan.md": "---\nissue_key: ERM-1234\nteam: spitfire\necs_story: \"non-ecs\"\ntestrail_section: 114774\ncase_count: 1\n---\n\n## TC-1: Create purchase order\n\n- **Tier:** criticalPath\n- **Automatable:** Yes\n- **ECS:** No\n\n**Preconditions**\n- User with Acquisitions module access is logged in\n- At least one active vendor record exists in the system\n\n**Steps**\n1. **Action:** Navigate to the Orders module and open Purchase orders\n   **Expected:** Purchase orders list is displayed with New button visible\n2. **Action:** Click New to open the order creation form\n   **Expected:** Order creation form opens with all required fields empty\n3. **Action:** Fill in Vendor and Order type then click Save and close\n   **Expected:** Order is saved with status Pending and appears in the list\n"
  }
}
EOF
```

**To run automation only, on existing TestRail cases (start at `automation-select`):**

No artifacts needed: the step reads the case ids from the trigger payload and fetches the cases
from TestRail itself. `areas` is optional; it narrows the explorer's file list (e.g. `["eureka"]`,
`["inventory"]`).

```bash
curl -s -X POST http://localhost:8080/api/debug/partial-run -H 'Content-Type: application/json' -d '{"flowId":"cypress-factory","startStepId":"automation-select","triggerPayload":{"testCaseIds":["C656309"],"areas":["inventory"]}}'
```

The run stops at gate 2 with the automation report. Its branch is `factory/<firstCaseId>` in
`cypressTafWork/`.

### Cleanup / Revert

**Clean up a debug execution:**
```bash
# Find debug executions
curl -s http://localhost:8080/api/executions | jq '.[] | select(.flowId=="cypress-factory") | {id, status, currentStepIndex}'

# The existing reset script handles FK ordering
bash scripts/reset-executions.sh
```

Or via UI: executions that were manually seeded show up in the execution list normally — cancel or let them complete.

**Disable the endpoint:**
- Remove `FACTORY_DEBUG_ENABLED=true` from `.env`, restart — the `@ConditionalOnProperty` means `DebugPartialRunController` bean is not created and the route `/api/debug/partial-run` returns 404.

### Pros / Cons

| ✅ Pros | ❌ Cons |
|---------|---------|
| No rebuild/restart between iterations — just call the endpoint again | Requires implementing the controller (one-time ~1h effort) |
| No YAML authoring — artifacts go in the request body | Request body can be large for full fixture sets (~50KB) |
| Real workers, real connectors, real HITL gates | No authentication on the debug endpoint — only safe with `FACTORY_DEBUG_ENABLED` strictly off in production |
| Full audit trail and execution history | |
| Works for any step in any flow — not cypress-specific | |
| Zero production impact (endpoint literally does not exist when disabled) | |

---

## Other Approaches (for future reference)

### Approach A — Partial Flow YAML + `PreloadArtifactsWorker` (no engine changes)

#### How It Works

Create an alternate flow YAML (e.g. `cypress-factory-from-step6.yaml`) whose first step is a no-op worker that reads artifact content from the step's `config` block and emits them as named artifacts. All subsequent steps are real, unchanged workers.

A new `PreloadArtifactsWorker` (worker ID: `preload-artifacts`) reads its step's config map and emits every declared output as an artifact. The flow YAML's `config` block for that step contains the fixture content inline.

#### What Needs to Be Built

**1. New worker class** — `factory-app/src/main/java/org/folio/factory/app/debug/PreloadArtifactsWorker.java`

```java
@Component("preload-artifacts")
@ConditionalOnProperty(name = "factory.debug.enabled", havingValue = "true")
public class PreloadArtifactsWorker implements AgentWorker {
    @Override public String id() { return "preload-artifacts"; }

    @Override
    public AgentResult execute(AgentContext context) {
        Map<String, String> outputs = new LinkedHashMap<>();
        for (String name : context.expectedOutputs()) {
            String content = (String) context.config().get(name);
            if (content == null) throw new AgentExecutionException(
                "PreloadArtifactsWorker: no config entry for '" + name + "'");
            outputs.put(name, content);
        }
        return new AgentResult(outputs, Map.of());
    }
}
```

**2. New flow YAML** — e.g. `factory-flow-cypress/src/main/resources/flows/cypress-factory-from-step6.yaml`  
_(create a separate YAML per starting point; for Phase 2/3 use `cypress-factory-from-step9.yaml`, `cypress-factory-from-step12.yaml`, etc.)_

```yaml
id: cypress-factory-from-step6
name: Cypress Factory (from test-spec)
version: 1.0.0
triggers:
  - event_type: manual
input_schema:
  required: [issueKey]
agent_chain:
  - step_id: preload
    type: AGENT
    worker_id: preload-artifacts
    inputs: []
    outputs: [scope_manifest.md, context.md, fragment_library.md, examples.md, scenario_list.md]
    config:
      scope_manifest.md: |
        ---
        issue_key: ERM-1234
        # ... paste full content here
      context.md: |
        # Context
        # ... paste full content here
      fragment_library.md: |
        # ... paste full content here
      examples.md: |
        # ... paste full content here
      scenario_list.md: |
        ---
        testrail_section: 200
        ---
        # ... paste full content here

  - step_id: test-spec
    type: AGENT
    worker_id: cypress-e2e-spec-agent
    inputs: [scope_manifest.md, context.md, fragment_library.md, examples.md, scenario_list.md]
    outputs: [test_plan.md]

  - step_id: gate-1-review
    type: HITL_GATE
    gate:
      gate_id: gate-1-test-cases
      title: "QA: review generated test cases"
      reviewed_artifacts: [test_plan.md, scenario_list.md, scope_manifest.md]

  - step_id: testrail-sync
    type: AGENT
    worker_id: cypress-testrail-sync
    inputs: [test_plan.md, scope_manifest.md, scenario_list.md]
    outputs: [test_plan.md]
```

**3. Guard with env var** — add to `application.yaml`:
```yaml
factory:
  debug:
    enabled: ${FACTORY_DEBUG_ENABLED:false}
```

The `@ConditionalOnProperty` on the worker means: if `FACTORY_DEBUG_ENABLED` is absent or false, the worker bean is not created and `FlowRegistry` will refuse to load the partial YAML (unknown worker ID). Both the YAML and the worker must be present together.

#### Usage

1. Set `FACTORY_DEBUG_ENABLED=true` in your `.env` and `source .env`
2. Paste fixture artifact content into the YAML `config` block
3. Rebuild: `mvn install -DskipTests`
4. Start the app: `mvn spring-boot:run -pl factory-app`
5. Trigger the partial flow:
   ```bash
   curl -s -X POST http://localhost:8080/api/triggers/manual \
     -H "Content-Type: application/json" \
     -d '{"flowId":"cypress-factory-from-step6","payload":{"issueKey":"ERM-1234"}}'
   ```
6. `preload` step completes instantly → `test-spec` fires

#### Cleanup / Revert

- Remove `FACTORY_DEBUG_ENABLED=true` from your `.env`
- Delete or move `cypress-factory-from-step6.yaml` out of `resources/flows/` (FlowRegistry fails at startup if the worker doesn't exist)
- Restart the app — back to normal

#### Pros / Cons

| ✅ Pros | ❌ Cons |
|---------|---------|
| No engine changes | Requires restart for each new fixture (YAML baked at classpath scan) |
| Works with the normal trigger API | Fixture content embedded in YAML — verbose, awkward to edit |
| Full audit trail in the UI | Must remember to remove YAML before disabling debug mode |
| Real workers, real connectors | YAML file is a production artifact that must never be deployed without the guard |

---

### Approach C — Direct DB Seeding (zero code changes)

#### How It Works

Insert a `pipeline_execution` row with `current_step_index = N` and `status = 'PENDING'` directly into Postgres. Insert `artifact` rows for the required input artifacts. The poller picks up the execution within 2 seconds and the engine starts from step N.

#### Usage

```sql
-- Run inside the factory DB container:
-- docker exec -i factory-postgres-1 psql -U factory -d factory

DO $$
DECLARE
  eid UUID := gen_random_uuid();
BEGIN
  -- Create execution at step 6
  INSERT INTO pipeline_execution
    (id, flow_id, flow_version, status, current_step_index,
     trigger_payload, retry_counts, next_run_at, version, created_at, updated_at)
  VALUES
    (eid, 'cypress-factory', '1.0.0', 'PENDING', 6,
     '{"issueKey":"ERM-1234"}', '{}', now(), 0, now(), now());

  -- Seed all 5 inputs for test-spec
  INSERT INTO artifact (id, execution_id, name, version, content_type, content, sha256, created_by, created_at)
  VALUES
    (gen_random_uuid(), eid, 'scope_manifest.md',  1, 'text/markdown', '...(content)...', 'manual', 'debug:sql', now()),
    (gen_random_uuid(), eid, 'context.md',          1, 'text/markdown', '...',             'manual', 'debug:sql', now()),
    (gen_random_uuid(), eid, 'fragment_library.md', 1, 'text/markdown', '...',             'manual', 'debug:sql', now()),
    (gen_random_uuid(), eid, 'examples.md',          1, 'text/markdown', '...',             'manual', 'debug:sql', now()),
    (gen_random_uuid(), eid, 'scenario_list.md',    1, 'text/markdown', '...',             'manual', 'debug:sql', now());

  RAISE NOTICE 'Created debug execution %', eid;
END $$;
```

**Cleanup:**
```sql
-- Note: artifact table has no ON DELETE CASCADE — delete artifacts first
DELETE FROM artifact WHERE execution_id = '<eid>';
DELETE FROM hitl_review WHERE execution_id = '<eid>';
DELETE FROM pipeline_execution WHERE id = '<eid>';
```

#### Pros / Cons

| ✅ Pros | ❌ Cons |
|---------|---------|
| Zero code changes | Multi-line markdown content is painful in SQL strings |
| Works on any environment with DB access | No validation against flow descriptor (typo in step index = confusing failure) |
| | Incomplete audit trail (no `EXECUTION_STARTED` audit event) |
| | Requires `pgcrypto` for correct sha256 computation |

Best used for incident investigation when you cannot change or restart the running app.

---

### Approach D — Direct Worker Unit Test (no engine, no DB)

#### How It Works

Instantiate the worker class directly, construct an `AgentContext` with fixture content, and call `worker.execute(context)`. No Spring context, no DB, no engine. The existing `TestRailSyncAgentWorkerTest` demonstrates this pattern exactly.

#### Usage Example

```java
class E2eSpecWorkerFixtureTest {

    private static final String SCOPE_MANIFEST = Files.readString(
        Path.of("src/test/resources/fixtures/scope_manifest.md"));
    // ... load all 5 fixtures

    @Test
    void testWithFixtures() throws Exception {
        var context = new AgentContext(
            UUID.randomUUID(), "test-spec",
            Map.of(
                "scope_manifest.md",  new ArtifactContent("scope_manifest.md",  1, "text/markdown", SCOPE_MANIFEST),
                "context.md",          new ArtifactContent("context.md",          1, "text/markdown", CONTEXT_MD),
                "fragment_library.md", new ArtifactContent("fragment_library.md", 1, "text/markdown", FRAGMENT_LIB),
                "examples.md",         new ArtifactContent("examples.md",         1, "text/markdown", EXAMPLES_MD),
                "scenario_list.md",    new ArtifactContent("scenario_list.md",    1, "text/markdown", SCENARIO_LIST)
            ),
            null, Map.of(), List.of("test_plan.md"));

        // Real LLM call — requires ANTHROPIC_API_KEY (or CodeMie running)
        AgentResult result = worker.execute(context);

        assertThat(result.outputs()).containsKey("test_plan.md");
    }
}
```

Fixture files live in `factory-flow-cypress/src/test/resources/fixtures/` — copy content from a real prior run once and reuse.

**Run a single test:**
```bash
mvn -pl factory-flow-cypress test -Dtest=E2eSpecWorkerFixtureTest
```

#### Pros / Cons

| ✅ Pros | ❌ Cons |
|---------|---------|
| Fastest feedback — no poller latency | Does not exercise HITL gates or downstream steps |
| Works today, no new code needed | Does not validate output format consumed by downstream workers |
| Easiest to parameterize | Fixture `AgentContext` must be updated if context API changes |

Best for: iterating on prompt content where you want fast LLM response feedback without running the full engine.
