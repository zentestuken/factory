# Merge notes — `cypress-flow-consolidated` → `main`

For anyone who pulls or rebases onto `main` after this branch lands.

**TL;DR:** four record components added (a compile error each, one line per call site), two
connector interfaces gained an abstract method (only bites custom implementations), and two
behaviour changes that are both improvements. Everything else in `factory-core`,
`factory-agents` and `factory-connectors` is additive and backward compatible at runtime.

Scope, so you can stop reading early:

| Section | Who it affects |
|---|---|
| §1 records + connector interfaces | **Everyone** who pulls hits §1a-c (compile error until fixed). §1e only if you construct `TestRailCase`. §1d only if you wrote a custom Jira/GitHub connector. |
| §2 gate retry | Anyone whose flow has a HITL gate. Nothing to do, but the "Reject and Re-generate" button now appears on your gates too — read it. |
| §3 additive changes | Nobody — listed so you do not have to re-derive that they are safe. |
| §4 cypress deletions | Only if you reused a cypress `worker_id`, or you have parallel work on the cypress flow. Flow modules do not depend on each other, so building a *different* flow is unaffected. |
| §5 Test Factory | Only the Test Factory owner. |

---

## 1. REQUIRED — four records gained a component, two interfaces gained a method

Java records have no optional or defaulted constructor parameters: the component list *is* the
canonical constructor signature, so positional construction must pass something for every
component. All three compact constructors normalise `null`, so `null` is a valid argument — but
the call site still has to be edited. "Optional" below always means *optional in the YAML or
config*, never optional in Java.

The descriptor keys bind through the existing `PropertyNamingStrategies.SNAKE_CASE` mapper in
`FlowDescriptorParser`, so no YAML file needs changing; omitting a key behaves exactly as before.

### 1a. `StepDescriptor` — `optionalInputs`

**Who is affected:** anyone who constructs `StepDescriptor` positionally — almost always in
controller/registry tests or a hand-built fake flow descriptor.

```java
// before — 9 components
new StepDescriptor(stepId, type, workerId, gate, subFlow,
                   inputs, outputs, config);

// after — 10 components; optionalInputs sits between inputs and outputs
new StepDescriptor(stepId, type, workerId, gate, subFlow,
                   inputs, List.of(),      // ← optionalInputs
                   outputs, config);
```

`null` is accepted and normalised to `List.of()` by the compact constructor, but `List.of()`
reads better.

**Symptom:** `constructor StepDescriptor in record StepDescriptor cannot be applied to given
types; reason: actual and formal argument lists differ in length`.

**Fix:** insert `List.of()` before the `outputs` argument at each call site. In this repo that
was six files; `git grep -l "new StepDescriptor("` finds yours.

### 1b. `HitlGateSpec` — `referencePrompts`

**Who is affected:** anyone who constructs `HitlGateSpec` positionally — same population as
above, and usually the same files.

```java
// before — 4 components
new HitlGateSpec(gateId, title, reviewInstructions, reviewedArtifacts);

// after — 5 components
new HitlGateSpec(gateId, title, reviewInstructions, reviewedArtifacts,
                 List.of());            // ← referencePrompts
```

`null` is normalised to `List.of()`. In this repo three test files needed the extra argument;
`git grep -l "new HitlGateSpec("` finds yours.

**What the field does:** a list of prompt ids whose templates define automated checks applied
*before* the gate — a reviewer agent's checklist, typically. The gate page renders them as
links to `/prompts/{id}` under an "Automated checks already applied" note, so a human can read
the criteria their approval is resting on. Purely presentational; the engine only copies the
list into the review package JSON. Declare it in YAML as:

```yaml
gate:
  gate_id: my-gate
  reviewed_artifacts: [my_artifact.md]
  reference_prompts: [my-reviewer-prompt]     # optional
```

The id must be a prompt *directory* under `classpath*:prompts/<id>/*.md` — `PromptCatalog`
scans directories, not worker ids, so a prompt with no worker of the same name still resolves.

### 1c. `TestRailProperties` — `suiteId`

**Who is affected:** anyone constructing `TestRailProperties` positionally. It is a
`@ConfigurationProperties` record, so **runtime binding is unaffected** — a config file with no
`factory.connectors.testrail.suite-id` binds it as `null` exactly as before. Only Java call
sites break, which in practice means tests.

```java
// before — 4 components
new TestRailProperties(baseUrl, username, apiKey, projectId);

// after — 5 components
new TestRailProperties(baseUrl, username, apiKey, projectId, null);   // ← suiteId
```

`suiteId` selects the TestRail suite for multi-suite projects; `null` keeps the previous
single-suite behaviour.

### 1e. `TestRailCase` — `steps`

**Who is affected:** anyone constructing `TestRailCase` positionally — in practice tests, since
the only production call site is `TestRailRestConnector.getCases`.

```java
// before — 3 components
new TestRailCase(id, title, preconditions);

// after — 4 components
new TestRailCase(id, title, preconditions, List.of());   // ← steps
```

`steps` is `List<TestRailStep>` (`TestRailStep(action, expected)`, also new). The compact
constructor normalises `null` to an empty list, so `null` is valid.

Nothing about fetching changed: `get_cases` already returned step content and the connector
simply discarded it. The Cypress flow now reads it so duplicate detection can judge a case on
its body rather than its title. `TestRailRestConnector` handles both step templates —
`custom_steps_separated` (structured) and a `custom_steps` blob, which becomes one step with an
empty `expected`.

### 1d. `JiraConnector` and `GitHubConnector` gained an abstract method

**Who is affected:** anyone who wrote their **own implementation** of either interface. If you
only use the shipped `*RestConnector` / `UnconfiguredConnectors` beans, there is nothing to do.

| Interface | New method |
|---|---|
| `JiraConnector` | `List<JiraIssue> getLinkedIssues(String issueKey)` |
| `GitHubConnector` | `List<String> listDirectory(String repo, String branch, String path)` |

Both are **abstract, not `default`** — deliberately, so a custom connector cannot silently
return nothing for a capability a flow depends on. A custom implementation therefore fails to
compile until it adds the method. Contrast `TestRailConnector`, whose seven new methods *are*
`default` (they throw `UnsupportedOperationException`) and so break nothing — see §3.

---

## 2. BEHAVIOUR CHANGE — "Reject and Re-generate" is now flow-agnostic

`HitlRetryService` used to carry a hardcoded, cypress-specific artifact list. It now derives the
carry-over set from the restart step's own declaration:

```java
carryOver = (startStep.inputs() ∪ startStep.optionalInputs())
            − {"$trigger", "hitl_feedback.md"}
```

**What this means for your flow:** if your flow has a HITL gate immediately preceded by an
`AGENT` step, retry now works correctly for it with **no control-plane change** — whatever that
step declares it can read is what gets seeded into the retry execution. That was not true
before.

Two consequences worth knowing:

- Declare on the restart step, as `optional_inputs`, anything you want the retry to see —
  including the artifact the step itself outputs, if you want the agent to revise rather than
  regenerate. The cypress flow does exactly this with `test_plan.md`.
- `hitl_feedback.md` is written by the service from the reviewer's comments. Declare it as an
  `optional_input` if your worker should read it; never as a required input, or fresh runs fail.

**The button is flow-agnostic too.** `ReviewUiController` used to check `flowId ==
"cypress-factory" && gateId == "gate-1-test-cases"`. It now calls `HitlRetryService.canRetry()`,
which answers from the descriptor alone:

> the review is `PENDING`, its step index points at a `HITL_GATE` step, and the step before that
> gate is an `AGENT` step.

No flow id or gate id is named anywhere in the retry path. Requiring the reviewed step to *be*
a gate is also what keeps escalation reviews out — an escalation's step index points at the
AGENT step that failed, not at a gate.

**This changes the Test Factory UI.** Both of its gates are preceded by AGENT steps
(`test-spec → qa-plan-review`, `test-execution → qa-signoff`), so both now show "Reject and
Re-generate". That is the intended generalisation, but note what it does and does not do:

- It **does** restart from the preceding AGENT step with that step's declared artifacts carried
  over at their latest version, and writes the reviewer's comments to `hitl_feedback.md`.
- It **does not** make the agent read that feedback. A worker only sees `hitl_feedback.md` if
  the step declares it under `optional_inputs` *and* the worker reads it. Test Factory does
  neither today, so a retry there re-runs the step with the same inputs — useful for a
  non-deterministic agent, not yet a feedback-driven revision.

To make retry actually feedback-driven in your flow, do what the cypress flow does: declare both
`hitl_feedback.md` and the step's own output artifact under `optional_inputs`, and have the
worker fold them into the prompt.

If you would rather your gate *not* offer the button, say so — the clean fix is an explicit
opt-out on the gate rather than re-introducing a flow-id check.

---

## 2b. BEHAVIOUR CHANGE — two shared LLM defaults moved

Found by sweeping `factory-app/src/main` against the merge base (the check promised when this
doc was first written). The console itself came out clean — **nothing under
`factory-app/src/main` names the cypress flow**, so the gate, the retry service and the review
page stayed flow-agnostic. Two defaults in `application.yaml` did change for everyone, though,
and neither is additive:

| Change | What it means for your flow |
|---|---|
| Anthropic default model `claude-sonnet-4-5` → `claude-sonnet-4-6` | Any flow that does not set `FACTORY_LLM_MODEL` now runs on a different model. Test Factory included. Pin `FACTORY_LLM_MODEL=claude-sonnet-4-5` if you want the old behaviour while you re-baseline. |
| New `spring.ai.model.timeout: ${FACTORY_LLM_TIMEOUT:300s}` | Previously unset, so Spring AI's own default applied. **This stays low on purpose** - fail-fast suits small calls, and raising it globally would change every flow's failure mode. A flow with large calls overrides it **per request** instead: `AnthropicChatOptions.Builder.timeout(Duration)` is read by `AnthropicChatModel` (verified in its bytecode), and `TestCaseGenAgentWorker.chatOptions()` sets 15 minutes for the cypress flow. Worth knowing why: a client-side cancel stops neither the server's work nor the billing, so a timeout firing on a slow-but-working call pays full price for a discarded response - execution 6e2abf8c burned ~$2 that way. |

## 3. Additive — no action needed

| Change | Why it is safe |
|---|---|
| `optional_inputs` on `AGENT` steps (`ExecutionEngine`) | Resolved as latest-version-if-present, after required inputs. Absent from a descriptor → empty list → identical behaviour. |
| `AuditEventType.HITL_RETRY` | `audit_event.event_type` is `varchar(50)` with no CHECK constraint and no Postgres enum — **no migration needed**. `UiFormat`'s switches have `default ->` arms, so `/audit` renders it. |
| `AbstractLlmAgentWorker`: `maxTokens()`, `callForEntity(promptId, …)`, `callForText(promptId, …)`, `drainUsage()` | `maxTokens()` returns `null` by default (no options applied). Existing workers take the identical code path. |
| `AbstractLlmAgentWorker.chatOptions()` | New hook returning `ChatOptions.Builder<?>`, or `null` for defaults. **`maxTokens()` is deliberately kept**: the default `chatOptions()` builds from it, so an existing `maxTokens()` override keeps working untouched. Replacing the hook outright would have been a *silent* break — the override would still compile and simply never be called — which is why it was not done that way. |
| Prompt-cache token metrics | `AgentResult` metrics gain `cacheWriteTokens`/`cacheReadTokens`, and only when non-zero. Read through the **portable** `Usage` interface (`getCacheReadInputTokens`/`getCacheWriteInputTokens` are `default` methods there), so `factory-agents` stays provider-neutral and non-caching providers simply report nothing. |
| `drainUsage()` now returns `TokenUsage` instead of `long[]` | `drainUsage()` itself is new on this branch, so nothing on `main` calls it. The record carries the two cache counts a `long[2]` could not. |
| `EngineMetrics.recordLlmTokens` also reads `cacheWriteTokens`/`cacheReadTokens` | Adds `factory.llm.tokens{type=cache_write}` and `{type=cache_read}`. No signature change; absent keys record nothing, so flows that do not cache are unaffected. |
| `PromptCatalog.sourceDirectory(workerId)` | New method returning the repo-relative path of a prompt directory (`factory-flow-cypress/src/main/resources/prompts/<id>`), recovered from the resource URI so a gate can tell a human where to edit a checklist. Falls back to the conventional `prompts/<id>` when the module cannot be determined. Purely additive; `PromptRef` is unchanged. |
| `ReviewUiController` constructor gained a `PromptCatalog` parameter | Only affects code that constructs it directly, which in practice means `ReviewUiControllerTest`. Spring injects it everywhere else. |
| `--link` / `--link-visited` CSS tokens | Added to `tokens.css` in both theme blocks. New tokens only — no existing token or rule changed. They exist because the global `a { color: inherit; text-decoration: none }` made links inside muted prose unrecognisable. |
| `AbstractLlmAgentWorker.callForText(Map)` now applies `chatOptions()` | **Small behaviour change.** This overload previously bypassed option building entirely, so it silently ignored `maxTokens()`. It has no callers anywhere in the repo, but if you added one on your branch it will now get the worker's options applied — which is what it should always have done. The `callForText(promptId, …)` overload already behaved this way. |
| `JiraConnector.getIssue(String issueKey, boolean withRenderedBody)` | New `default` overload delegating to the single-argument `getIssue`, so **nothing changes for an existing caller or an existing connector** - both keep the exact request they always made, and a custom connector that ignores the flag simply returns the un-rendered issue. Only the REST implementation acts on it, adding `?expand=renderedFields`; the HTML lands in `JiraIssue.raw()` under `renderedFields.description`, so `JiraIssue` is unchanged. Opt in only if you need it: the response is noticeably larger. |
| `JiraConnector.downloadAttachment(String contentUrl)` | `default`, throwing `UnsupportedOperationException`, so a custom connector still compiles and only fails if a flow actually asks for attachment bytes. `UnconfiguredConnectors.Jira` returns `null` (advisory mode has no credentials, which is not an error). Returns `null` on any failure rather than throwing - callers are enriching a prompt, not reading a required input. **If you implement this yourself, note that Jira answers the attachment content endpoint with a 303 to a pre-signed `api.media.atlassian.com` URL** - the bytes are only on the second hop, and `RestClient` does not follow redirects, so a one-hop implementation returns an empty body. The shipped implementation follows up to 3 hops and uses a second, credential-free client (the builder cloned before the Authorization default is added) for every hop after the first, so Basic auth is never forwarded to the media host. It deliberately does **not** enable redirect-following on the shared client, which would forward credentials on every other Jira call too. **`main` has its own `downloadAttachment(String attachmentId, long maxBytes)`** (dev-factory, hand-followed redirects with an origin allow-list); the two overloads coexist and neither replaces the other. They share `JiraRestConnector`'s constructor, where the order matters: the credential-free `mediaClient` must be cloned in the 3-arg constructor **before** the base URL and Authorization default are applied. The 2026-11-09 merge of `main` into `cypress-flow` got this wrong (cloned after) - credentials went to the media host and base-URL-only (anonymous) mode crashed; `JiraRestConnectorTest.downloadAttachmentFollowsThe303ToTheMediaHost` is the test that catches it. |
| `AbstractLlmAgentWorker.media(String promptId, Map<String, Object> variables)` | New hook returning `List.of()` by default. When it returns empty, `buildPrompt` keeps the exact `spec.user(String)` call it always made, so every existing worker takes a byte-identical path. Media is passed through the prompt **variables**, not a field, because workers are stateless singletons shared across executions. Note if you override it: media rides in the user message and prompt caching is system-only, so each attachment is re-sent on every attempt of a retry loop; and a model without image support **rejects** such a call rather than ignoring the attachment, so the override owns that decision. |
| `TestRailConnector.getCasesByRefs(String ref)` | `default`, throwing `UnsupportedOperationException`; `UnconfiguredConnectors.TestRail` returns an empty list. The REST implementation is `get_cases/{project}&suite_id=…&refs=<ref>`, sharing the paging loop with `getCases(sectionId)`. Used by the cypress coverage prefetch to find cases that already cite a story. |
| `TestRailConnector` gained 7 methods | All `default`, throwing `UnsupportedOperationException`. A custom implementation still compiles; it only fails if a flow calls the new surface. The 3 pre-existing abstract methods are unchanged. |
| `TestRailRestConnector.resolveSectionByPath` — `parent_id: 0` fix | TestRail occasionally returns `parent_id: 0` for root sections instead of `null`. The previous code only treated `null`/missing as the sentinel, so affected root sections were stored under key `0L` but looked up under `-1L`, silently failing every path that started from a root section. Fixed to treat `0` the same as `null`. No interface change; no call-site impact. |
| `JiraConnector.getLinkedIssues`, `GitHubConnector.listDirectory` | **Not additive if you wrote your own connector** — these are abstract, not `default`. Implemented by the shipped REST and `UnconfiguredConnectors` beans, so stock users are unaffected. See §1d. |
| `ExecutionEngine` retry log now passes the exception | Stack traces appear on step-retry warnings. No control-flow change. |
| `DebugPartialRunController` | `@ConditionalOnProperty(factory.debug.enabled)` — the bean does not exist unless you set it. |
| `FACTORY_CYPRESS_LLM_TIMEOUT` and `FACTORY_CYPRESS_SEND_IMAGES` | New `CypressFlowProperties` fields. Both are `default`-safe: `LLM_TIMEOUT` falls back to 15 minutes, `SEND_IMAGES` to `true`. The timeout is applied per-request via `AnthropicChatOptions.Builder.timeout()`, overriding the shared `FACTORY_LLM_TIMEOUT` for this flow only. |
| Gate UI: original artifact now `<details>` collapsed | The read-only `<pre>` block in `review.html` is wrapped in `<details>` (collapsed by default); the editable `<textarea>` is the primary view. Visual change only — `Amend` logic and all existing gate behaviour are unchanged. Affects every flow's gate page. |
| `PromptDump` retry-numbering | A second `Run` for the same execution now seeds its sequence counter from existing files, so engine-level step retries append to the dump directory rather than overwriting it. Debug aid only; no production behaviour change. |
| `review_instructions` now render through `UiFormat.instructionBlocks()` | Gate instructions were previously emitted as one block of text; they are now split into paragraphs and bullet lists. **Every flow's gate page is affected, including Test Factory's** — it is a rendering change only, no descriptor change, and plain prose still renders as one paragraph. Worth a glance at your gate after merging if your instructions lean on manual line breaks. |
| Gate footer lists `reference_prompts` | Shown only when a gate declares them, so a descriptor that does not is unchanged. Its purpose is to tell a reviewer which automated checks already ran and where to edit them — see §1b. |

### One trap worth knowing about `optional_inputs`

Optional inputs resolve to the **latest** artifact version. A step that declares the same
artifact as both an optional input and an output will therefore see its own previous output when
the step re-runs. The cypress flow relies on this for gate retry. If you copy the pattern
without wanting that, don't declare your own output as an optional input.

### Prompt caching — the pattern to copy, and the one thing not to

`factory-flow-cypress` now declares `spring-ai-anthropic` in **its own pom** and returns an
`AnthropicChatOptions` from `chatOptions()` to enable Anthropic prompt caching on the system
prompt. `factory-agents` still depends only on the provider-neutral `spring-ai-client-chat`.

Keep it that way. `spring.ai.model.chat` is bound to `${FACTORY_LLM_PROVIDER:anthropic}` and the
OpenAI starter is shipped for Groq/Gemini/OpenRouter/Ollama, so provider swapping is a live
capability — putting a provider-specific class in `factory-agents` would break it for everyone.

Running the cypress flow on a non-Anthropic provider is safe: `OpenAiChatModel` reads only the
portable `ChatOptions` properties and logs `"... is not supported ... Ignoring."` for the rest,
so `maxTokens` still applies and caching silently does not happen. Expect those warnings; they
are not a bug.

---

## 4. Deleted from `factory-flow-cypress` — read only if one of these is you

Flow modules do not depend on each other; only `factory-app` aggregates them. So deleting
cypress internals **cannot break a different flow module you are building**. There are exactly
two ways this section matters:

**(a) You reused a cypress worker id in your own flow YAML.** This is a sanctioned pattern —
CLAUDE.md says "existing worker ids are reusable across flows" — and it is the one realistic
way these deletions reach someone else. A descriptor referencing a removed `worker_id` fails
`FlowRegistry` validation **at startup**, not at build time, so `mvn verify` stays green and the
failure shows up in the boot log. Check your descriptors against this table:

| Deleted class | Worker id |
|---|---|
| `ContextLoaderWorker` | `cypress-context-loader` |
| `FragmentScannerWorker` | `cypress-fragment-scanner` |
| `CoverageLoaderWorker` | `cypress-coverage-loader` |
| `CypressExamplesLoaderWorker` | `cypress-examples-loader` |
| `ScenarioAnalysisAgentWorker` | `cypress-scenario-analysis` |
| `E2eSpecAgentWorker` | `cypress-e2e-spec-agent` |

If you depended on one, the logic survives as plain services under
`org.folio.factory.cypressfactory.prefetch` (`AreaDetector`, `ContextPrefetch`,
`FragmentPrefetch`, `CoveragePrefetch`, `ExamplesPrefetch`). They are constructor-injected and
reusable — they are simply no longer `AgentWorker` beans, so you would call them from your own
worker rather than name them in a descriptor.

**(b) You have parallel work on the cypress flow itself.** Then expect real conflicts, and this
is the shape of them. Also removed: `model/CypressTestPlan.java` (the flow no longer uses
structured output) and the `cypress-e2e-spec-agent` / `cypress-scenario-analysis` prompt
directories.

Artifacts the cypress flow no longer produces: `scope_manifest.md`, `context.md`,
`fragment_library.md`, `examples.md`, `existing_coverage.md`, `scenario_list.md`,
`testcase_review.md`, `previous_test_plan.md`. It now emits `test_plan.md` only (three sections:
`# Analysis`, `# Test Cases`, `# Review`), plus `hitl_feedback.md` on retry runs. Anything of
yours that read one of those artifact names needs repointing.

**`triage-agent` is not affected.** The cypress descriptor stopped referencing it, but the
worker itself still exists, unchanged, in `factory-flow-test-factory` — so a flow of yours that
reuses `triage-agent` keeps working.

---

## 5. Heads-up on Test Factory

Earlier on this branch, `triage-agent`'s prompt and `ScopeManifest` were extended to carry
acceptance criteria verbatim. That was motivated by the cypress flow, which no longer uses
`triage-agent` at all — so the change now affects **Test Factory only**. It is additive
(`acceptance_criteria` in the scope manifest) and all Test Factory tests pass, but if you own
that flow, be aware the manifest shape grew for a reason that no longer applies. Reverting it is
safe if you would rather keep the diff honest.

---

## 6. Sanity check after merging

```bash
mvn install -DskipTests   # catches the StepDescriptor break immediately
mvn verify                # needs Docker/Podman for Testcontainers
```

Then start the app and confirm your flow still registers — `FlowRegistry` validates every
descriptor's `worker_id`s against the `AgentWorkerRegistry` at startup, so a stale reference
surfaces in the boot log rather than at trigger time.
