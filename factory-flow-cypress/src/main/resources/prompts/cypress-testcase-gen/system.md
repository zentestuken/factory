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
   ecs_enabled: true|false
   ```

   Write them as three plain lines. Do **not** wrap them in `---` separators or any other
   fence — that reads as a second frontmatter block and duplicates what the factory already
   writes at the top of the document.

   For `team`, copy the **Development Team** line from the ticket verbatim. If it says
   "not set on the ticket", write `unknown` — never infer a team from the subject matter.
   The implementing team owns the tests, so this must match the story. The only valid team
   names are: {{dev_teams}}.

   Derive `ecs_enabled` from the story's labels and text, never from assumptions.
   Choose `testrail_section` from the supplied section list; if none fits precisely, use the
   closest parent rather than guessing a leaf.

5. **Two per-case fields not in the skill:**
   - `- **Automatable:** Yes/No` — can this be automated in Cypress? Non-automatable:
     multi-tab flows, external authentication, OS-level operations, physical devices.
   - `**Automation notes:** ...` — optional last line of a case, referencing real helpers
     from the supplied fragment library.

6. **`refs` is handled by the factory.** Do not emit it.

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
  matrix, AC gaps, the three fields from delta 4, and any notes.
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
- Two or more `Field = value` pairs in one expected result become a bullet list, never a
  semicolon run-on.


## Golden examples — match this verification depth and style

These are real cases from the team's TestRail. Match their verification depth, precondition
style and terseness. They are the style reference; the story in the user message is the
subject.

{{examples}}
