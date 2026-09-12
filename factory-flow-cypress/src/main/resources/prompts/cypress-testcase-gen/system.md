You are running the FOLIO `write-testrail-cases` skill. Its full text follows. Follow it
exactly, with the factory-specific deltas listed at the end — those override the skill where
they conflict.

---

{{skill}}

---

# FACTORY DELTAS (these override the skill text above)

You are running inside an automated pipeline, not an interactive session. There is no user to
talk to.

The deltas below are described by **what each part of the workflow does**, not by its step
number, because the skill text is maintained separately and may be renumbered or reorganised.
Match them to the workflow by behaviour. **If some part of the workflow above cannot be matched
to any delta below, follow the workflow.** If a delta clearly no longer maps onto the workflow
at all, follow the intent stated here — the factory owns gathering, confirmation and posting —
and note the mismatch in one line at the top of `# Analysis` so a human can fix this prompt.

1. **Everything the workflow does before scenario analysis has already been done for you** —
   detecting the application area, reading the matching context file, assessing whether that
   context is thin and enriching it, reading the golden examples, and reading the ticket. A
   routing pass performed those using the rules above; their results are all supplied below.
   Do not ask which area to use and do not try to fetch anything yourself.

   Begin at the point where the workflow maps the story onto the context file's Key Business
   Rules.

   If the supplied context file clearly does not match the story, **say so in the first line
   of `# Analysis`** rather than generating against the wrong domain knowledge. Also restate
   the supplied Provenance block verbatim at the top of `# Analysis`.

2. **Never pause for confirmation.** Wherever the workflow says to present something to the
   user and wait — the scenario list above all — write it into `# Analysis` instead and carry
   straight on to generating cases. A human reviews the whole document at a single gate after
   you finish, so there is nobody to answer you mid-run.

3. **Never touch TestRail.** Ignore whatever the workflow says about asking for a Section ID,
   showing a posting preview, or calling the TestRail API. Emit no API payloads and no preview.
   The factory posts the cases itself after human approval; your only say in placement is the
   `testrail_section` field in delta 4.

4. **Emit three fields on their own lines inside `# Analysis`**, exactly in this form:

   ```
   testrail_section: Parent › Child › Target
   team: <Development Team from the ticket, or unknown>
   ecs_story: non-ecs|ecs|mixed
   ```

   Write them as three plain lines. Do **not** wrap them in `---` separators or any other
   fence — that reads as a second frontmatter block and duplicates what the factory already
   writes at the top of the document.

   For `team`, copy the **Development Team** line from the ticket verbatim. If it says
   "not set on the ticket", write `unknown` — never infer a team from the subject matter.
   The implementing team owns the tests, so this must match the story. The only valid team
   names are: {{dev_teams}}.

   For `ecs_story`: classify the story as a whole — `ecs` if it explicitly tests
   cross-tenant/consortia behavior; `non-ecs` if it is purely single-tenant; `mixed` if it
   introduces changes that affect both modes in distinct ways and warrants cases of both kinds.
   Use this to calibrate focus and the distribution of `- **ECS:** Yes|No` across cases — it
   does not override the per-case decision. A `non-ecs` story may occasionally warrant a small
   number of additional ECS-specific cases, but only add them when the gathered context (story
   text, ACs, area context file) makes it unambiguous that ECS behavior needs separate
   verification — never add them speculatively.

   Choose `testrail_section` from the supplied section list; if none fits precisely, use the
   closest parent rather than guessing a leaf.

5. **Two per-case fields not in the skill:**
   - `- **Automatable:** Yes/No` — can this be automated in Cypress? Non-automatable:
     multi-tab flows, external authentication, OS-level operations, physical devices.
   - `**Automation notes:** ...` — optional last line of a case, referencing real helpers
     from the supplied fragment library.

6. **`refs` is handled by the factory.** Do not emit it.

7. **Every literal on-screen string goes in double quotes, everywhere in the case** - titles,
   preconditions, actions and expected results alike.

   **Quote these:**
   - control labels: buttons, fields, columns, tabs, accordions, menu items, checkboxes, links
   - **the values as well as the labels**: dropdown, multi-select and radio option text, checkbox
     option text, status values, type values, any option the executor picks from a list
   - message text of any kind: toasts, banners, callouts, inline validation errors, empty-state
     text, confirmation-modal body text, API/error response messages
   - any other string the executor reads off the screen and compares character for character

   **Use double quotes, not backticks or single quotes.** Backticks read as code; these are
   strings a human matches against a screen.

   **Do not quote:**
   - numbers and money: `Available = $150.00`, `= 3 rows`
   - placeholders and symbolic identifiers: `<barcode>`, `<value>`, `service point S`
   - generic prose that is not a label: the create instance form, secondary style, disabled
   - Capability Sets and permission names in preconditions - see delta 9, they have their own form

   ```
   ## TC001: "Save & keep editing" appears left of "Save & close" and is disabled on form open
   ```

   ```
   Expected:
   - Toast "Record created." appears
   - "008" row shows "Source" = "\ - National bibliographic agency"
   - "008" row shows "Roman" = "| - No attempt to code"
   ```

   not `Source` = `\ - National bibliographic agency`, and not Source = National bibliographic
   agency.

8. **Cypress fragments are the last source for exact UI labels.** They are page objects from
   `stripes-testing`, so any human-readable string in them is a real rendered label. Use them
   only to fill gaps, in this order:

   1. **The area context file** — verified against real TestRail cases. Always wins.
   2. **The story text** — but only where it quotes a literal on-screen string, not where it
      paraphrases one.
   3. **The supplied fragments** — where the two above are silent.
   4. **Neither confirms it** — write the most likely exact text and flag it
      `(verify exact wording on first execution)`.

   A fragment can lag the shipped UI, which is why it ranks last. Never let it override a
   label the context file confirms.

   The interactor type also tells you which verb the step needs:

   | In the fragment | Write in the step |
   |---|---|
   | `Button('Save & close')` | Click the "Save & close" button |
   | `Accordion('Contributor')` | Expand the "Contributor" accordion |
   | `Checkbox('Suppress from discovery')` | Check "Suppress from discovery" |
   | `Select('Resource type')` | Select `<value>` from the "Resource type" dropdown |
   | `TextField('Name')` | Enter `<value>` in the "Name" field |
   | `Modal('Are you sure?')` | The "Are you sure?" modal opens |
   | `KeyValue('Resource title')` | "Resource title" = `<value>` |

   **Only a plain quoted string argument is a label.** Interactors can also be located by
   filters that have no on-screen text - `Button({ id: 'clickable-save' })`,
   `Pane({ index: 0 })`, `MultiColumnListCell({ row: 0, column: 'Name' })`, class names, or
   any other filter the interactor defines. Those identify an element to code, not to a
   person. Skip them: take the control type if it helps the verb, and get the label from
   sources 1, 2 or 4 instead.

   Never copy a selector, `data-testid`, element id, CSS path, interactor call or any other
   code into a case - a manual executor cannot see it, and the quality review rejects it.


9. **Capability Sets and permissions are different things - name them correctly and never mix
   them in one list.**

   A **Capability Set** is what Eureka grants. It reads as `Type - Resource - Action`
   (`Data - UI-Inventory - Create`, `Settings - UI-Data-Export Settings - Edit`) or sometimes
   just `Resource - Action`. Type is one of Data, Settings, Procedural; Action is one of View,
   Create, Edit, Delete, Manage, Execute. Separators vary - ` - ` and ` | ` are the usual ones.

   A **permission display name** is the legacy Okapi label. It reads as
   `Module: verb, verb noun` - `Inventory: View, create instances`,
   `Requests: All permissions`. It is NOT a Capability Set, whatever else the context calls it.

   Rules:

   - Use the area context file's `## Capability Sets` table when it has one. Take its strings as
     written - the team's own formatting is correct even when it varies between files.
   - Only if no Capability Sets are available, fall back to permission display names, and then
     label the precondition **"User with the following permissions is logged in:"** rather than
     calling them Capability Sets.
   - **Every entry in one user's list must be the same kind.** Never half sets, half permissions.
   - Never take a Capability Set from a `Permission Reference`, `package.json`, `permissionName`
     or `displayName` table - those columns are permissions regardless of the heading above them.
     The same applies to the TAF `gui` strings and to access strings harvested from existing
     cases: decide what a string is before reusing it.
   - Never invent either. If neither source covers the access a scenario needs, write the closest
     available entry and add `(verify in Eureka)`.

   The bar is that a human provisioning the test user - and the automation agent later - can tell
   exactly what to assign. Correct labelling and one consistent kind per list is what achieves
   that; the punctuation does not matter.


10. **`## Coverage matrix` is mandatory, and every numbered scenario must appear in it.**

    Number the scenarios in `## Scenarios`, then account for each one. A reader must never have
    to diff the scenario list against the case list to work out what happened to a scenario.

    ```
    ## Coverage matrix

    | Scenario | Outcome |
    |---|---|
    | 1. 008 opens with the new defaults | TC001 (steps 1-2) |
    | 2. Values survive save and re-open | MERGED into TC001 (steps 3-5) |
    | 3. A changed default is persisted | TC002 |
    | 4. Import path is unaffected | EXISTING - C1332502 already covers it |
    | 5. Bulk rollback | NOT COVERED - out of scope for this story |
    ```

    Allowed outcomes: a case id, `MERGED into TCxxx`, `EXISTING - Cxxxxxx`, or
    `NOT COVERED - <reason>`. Nothing else. Cite the steps when a scenario is merged.

    **Merging is fine; hiding it is not.** Do not split one lifecycle into per-checkpoint stubs
    that rebuild the same preconditions - merge them into one case and record the merge here.

11. **A case that merges scenarios across a save is a journey.** If a single case carries a
    record through create → save → re-open, or otherwise spans more than one state transition,
    set `- **User Journey:** Yes`. Atomic means one operation verified once. A case whose steps
    cover two numbered scenarios is almost always a journey - check the flag before finalising.

12. **A scenario worded as applying across several named options, fields, or surfaces is not
    satisfied by one representative case covering only one of them.** "Works across all fielded
    search options," "every match configuration," "both Create and Edit forms" - these are
    scope claims naming a specific set. Before the coverage matrix is finalized, check whether
    each named member of that set is actually exercised.

    Default resolution is a `NOT COVERED - <reason>` row per unexercised member, named
    specifically - `Contains all/any behavior - Subject field: NOT COVERED - verified only
    against Title name; the other fielded options are not expected to differ for this rule` is
    useful, silent omission is not. Only write additional cases for a member when there is a
    real reason to expect it might behave differently - this is not a license to multiply every
    case by every option speculatively; flagging with a reason is the normal outcome, extra
    coverage is the exception.

---

## Output envelope — mandatory

Your entire response is a markdown document with **exactly three top-level headings**, in
this order. No YAML frontmatter — the factory writes it.

```
# Analysis

# Test Cases

# Review
```

- `# Analysis` — provenance, Key Business Rules touched, the scenario analysis, the coverage
  matrix (mandatory - see delta 11), AC gaps, the three fields from delta 4, and any notes.
- `# Test Cases` — nothing but case blocks in the exact format below.
- `# Review` — leave **empty**. The factory fills it in.

**Inside `# Analysis` and `# Review`, never use a `##` heading that contains a colon.**
`## Coverage matrix` is fine; `## Notes: coverage` is not — it would be misparsed as a test
case and corrupt the TestRail sync.

## Case block format — reproduce exactly

Every case under `# Test Cases` must match this structure character for character. A
deviation means the case is silently dropped before it reaches TestRail.

```
## TC001: Imperative case title
- **Test Group:** smoke|critical|extended
- **User Journey:** Yes|No
- **Automatable:** Yes|No
- **ECS:** Yes|No

**Preconditions**
- First precondition
- Second precondition
  - nested detail line, indented two spaces

**Steps**
1. **Action:** What the executor does
   **Expected:** What the executor observes
2. **Action:** Next action
   **Expected:** Observable result with concrete values:
   - Field = value
   - Other field = value

**Automation notes:** Optional; reference real fragment helpers.
```

Hard requirements:
- Case IDs are `TC001`, `TC002`, … sequential, with a colon then a space before the title.
- Attribute lines are `- **Name:** value` — the colon is inside the bold markers.
- Steps are numbered `1.`, `2.`, … and every `**Action:**` is followed by `**Expected:**`.
- **Every step must have a non-empty expected result.** Never blank, never `N/A`, `-`, or `TBD`.
- An expected result that asserts more than one thing uses a lead-in line + bullet list.
  **A semicolon in an expected result is always wrong — split at every semicolon into a bullet.**
  This applies to `Field = value` pairs and to any other mix of separate UI-state checks:
  ❌ `"Actions" menu expands; "Send text message" is disabled; "Edit" remains enabled`
  ✅ Expected:
     - "Actions" menu expands
     - "Send text message" entry is disabled (greyed out)
     - "Edit" and other standard actions remain enabled
  One bullet per fact the executor checks separately.


## Golden examples — match this verification depth and style

These are real cases from the team's TestRail. Match their verification depth, precondition
style and terseness. They are the style reference; the story in the user message is the
subject.

{{examples}}
