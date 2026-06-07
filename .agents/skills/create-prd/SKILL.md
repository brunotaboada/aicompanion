---
description: Interactive brainstorming session that produces a Product Requirements Document for a feature.
output: _prd.md
---

# create-prd

You are running the `create-prd` skill. Produce a Product Requirements Document for the
feature `{{feature}}` through interactive brainstorming with the user.

## Inputs

- Feature name: `{{feature}}`
- Feature directory: `{{feature_dir}}`
- Seed document (optional): `{{seed_file}}`
- Update mode: `{{update_mode}}` — when `yes`, an existing PRD is present and must be revised, not replaced.

## Outputs

- Final PRD → `{{output_file}}`
- Architecture Decision Records → `{{adr_dir}}/adr-NNN.md` (zero-padded; pick the next available number)

## References (read on demand)

- PRD template: `{{templates_dir}}prd-template.md`
- ADR template: `{{templates_dir}}adr-template.md`
- Question protocol: `{{templates_dir}}question-protocol.md`

## Hard rules

1. **Do not write the PRD file until the user explicitly approves the final draft.**
2. **One question per turn.** Wait for the user's answer before asking the next.
3. **Multiple-choice format** whenever the answer space is bounded. Render as:
   ```
   A) <option>
   B) <option>
   C) <option>
   D) <option>
   E) Other (please specify)
   ```
   Use `E` for free-form fallback. The aicompanion runtime does not have a button-based question tool — the user types `A`, `B`, `C`, or a free-text answer.
4. **WHAT and WHY only.** No databases, frameworks, libraries, APIs, schemas, or architecture. Those belong in the TechSpec.
5. **YAGNI.** Push back on scope creep. Every feature must justify its existence.
6. **Update mode** (`{{update_mode}} == yes`): read the existing PRD first, preserve sections the user does not ask to change.

## Workflow

### Phase 1 — Ground

Read the following (in parallel if your runtime supports it):

- `{{seed_file}}` if it exists
- `{{output_file}}` if `{{update_mode}}` is `yes`
- `{{templates_dir}}prd-template.md`
- `{{templates_dir}}question-protocol.md`
- Any existing ADRs in `{{adr_dir}}`

Acknowledge what you found in one short paragraph, then proceed.

### Phase 2 — Parallel research (Optional)

Do these concurrently (Ask the user if they want to do this):

- **Codebase**: explore the repo to surface existing architecture, patterns, and constraints that bear on this feature.
- **Market / web**: if you have web search, look for competitor approaches, user expectations, and relevant standards. If you don't, note in Phase 6's *Open Questions* what would benefit from market data.

Summarise findings in 3–5 bullets. Confirm with the user (one short message — no question yet) before continuing.

### Phase 3 — Clarify the need

Per the question protocol, ask one at a time:

- Who are the users?
- What problem do they have today?
- What does success look like for them?
- What is explicitly **out of scope**?

Skip a question if the seed document or existing PRD already answers it unambiguously.

### Phase 4 — Approaches

Present **2–3 distinct product strategies** with trade-offs. Ask the user to pick one.

For the chosen approach, write an ADR using `{{templates_dir}}adr-template.md` to `{{adr_dir}}/adr-NNN.md`. Use the next available number (zero-padded, three digits).

### Phase 5 — Draft

Generate the **complete** PRD inline (do not save to disk yet). Use `{{templates_dir}}prd-template.md` as the structure. Every section must reflect a confirmed decision; flag genuine unknowns in *Open Questions*.

### Phase 6 — Review

Ask:

```
A) Approve and save
B) Request changes
C) Discard and stop
```

- `A` → proceed to Phase 7.
- `B` → ask what to change, revise, present the full draft again, re-ask.
- `C` → stop without writing.

### Phase 7 — Save

Write the final PRD to `{{output_file}}`. Then in one short message tell the user:

> Done. Run `create-tech-spec {{feature}}` next.

## Stop signals

- User types `/abort` → stop immediately. Do not write any files.
- User types `/done` → if you have enough to draft, produce and save the draft. Otherwise stop with a one-paragraph summary of what is still needed.
- User types `/skip` in answer to a question → pick the most reasonable default and proceed; note the choice in *Open Questions*.

## Anti-patterns

- Multiple questions in one message.
- Implementation details (frameworks, schemas, endpoints, libraries).
- Writing the PRD file before approval.
- Skipping codebase exploration because the feature "looks simple".
- Overwriting an existing PRD in update mode instead of revising it.
