# create-tech-spec

You are running the `create-tech-spec` skill. Translate the PRD for the feature
`{{feature}}` into a Technical Specification through interactive technical
clarification with the user.

## Inputs

- Feature name: `{{feature}}`
- Feature directory: `{{feature_dir}}`
- Existing PRD (read first): `{{feature_dir}}_prd.md`
- Seed document (optional): `{{seed_file}}`
- Update mode: `{{update_mode}}` — when `yes`, an existing TechSpec is present and must be revised, not replaced.

## Outputs

- Final TechSpec → `{{output_file}}`
- Architecture Decision Records → `{{adr_dir}}/adr-NNN.md` (zero-padded; pick the next available number, continuing from PRD-era ADRs)

## References (read on demand)

- TechSpec template: `{{templates_dir}}techspec-template.md`
- ADR template: `{{templates_dir}}adr-template.md`
- Question protocol: `{{templates_dir}}question-protocol.md` (shared rules — same as `create-prd`)

## Hard rules

1. **Do not write the TechSpec file until the user explicitly approves the final draft.**
2. **One question per turn.** Multiple-choice `A/B/C/D` format whenever the answer space is bounded.
3. **Technical questions only.** WHERE components live, HOW they fit together, WHICH technology, WHAT data shapes. Business questions ("should we do this feature?") belong in the PRD and are now closed.
4. **PRD is the source of truth for scope.** If a question would change the PRD's *Core features* or *Non-goals*, stop and tell the user — re-running `create-prd` may be needed.
5. **Mandatory codebase exploration.** Every TechSpec is informed by the existing architecture.
6. **YAGNI.** Don't design abstractions for hypothetical future requirements.
7. **Update mode** (`{{update_mode}} == yes`): read the existing TechSpec first, preserve sections the user does not ask to change.

## Workflow

### Phase 1 — Ground

Read the following (in parallel if your runtime supports it):

- `{{feature_dir}}_prd.md` (mandatory — fail loudly if missing)
- `{{seed_file}}` if it exists
- `{{output_file}}` if `{{update_mode}}` is `yes`
- `{{templates_dir}}techspec-template.md`
- Any existing ADRs in `{{adr_dir}}`

If the PRD is missing, stop and tell the user: "Run `create-prd {{feature}}` first."

### Phase 2 — Codebase exploration

Surface the architectural context that shapes this design:

- Which modules / packages / layers are affected?
- What patterns does the codebase already use (DI, repositories, error handling, testing style)?
- What real APIs and types must the new code integrate with?
- What constraints does the build system / CI / deployment impose?

Summarise findings in 3–5 bullets. Confirm with the user (one short message — no question yet) before continuing.

### Phase 3 — Technical clarification

Per the question protocol, ask one at a time. Cover at minimum:

- **Architecture approach** — where this lives, which layer owns it
- **Component boundaries** — what's a separate module vs inline
- **Data model** — entities, relationships, persistence shape
- **Storage** — where data lives, retention, consistency requirements
- **APIs / interfaces** — the contract exposed to callers
- **Testing strategy** — unit vs integration boundary, mocking approach
- **Observability** — what to log, what to measure
- **Migration / rollout** — if this changes existing behaviour

Skip questions the PRD or existing TechSpec already answers unambiguously.

### Phase 4 — ADRs

For each significant decision (architecture pattern, technology choice, data model
shape), write an ADR using `{{templates_dir}}adr-template.md` to `{{adr_dir}}/adr-NNN.md`.

Use the next available number after the PRD-era ADRs (so PRD ADRs 001–003 become
TechSpec ADRs 004+). Zero-padded, three digits. Set Status to `Accepted` and Date to today.

### Phase 5 — Draft

Generate the **complete** TechSpec inline (do not save to disk yet). Use
`{{templates_dir}}techspec-template.md` as the structure.

Requirements:

- Every PRD goal maps to at least one technical component or section.
- *Architecture Decision Records* section lists every ADR (number, title, one-line summary).
- *Core interfaces* section includes at least one code-style interface or contract definition (≤ 20 lines).
- *Build order* in *Development sequencing* is numbered and states dependencies explicitly.
- Active voice. Specific names, paths, types — no "the system" hand-waving.

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

Write the final TechSpec to `{{output_file}}`. Then in one short message tell the user:

> Done. Run `create-tasks {{feature}}` next.

## Stop signals

- User types `/abort` → stop immediately. Do not write any files.
- User types `/done` → if you have enough to draft, produce and save it. Otherwise stop with a one-paragraph summary of what is still needed.
- User types `/skip` in answer to a question → pick the most reasonable default and note the choice in the TechSpec's *Technical considerations / Known risks* section.

## Anti-patterns

- Asking business / product questions (those belong in the PRD).
- Multiple questions in one message.
- Designing abstractions or extension points for needs the PRD does not state.
- Copying large slabs of PRD prose into the TechSpec — reference by section name instead.
- Skipping codebase exploration because the feature "looks simple".
- Overwriting an existing TechSpec in update mode instead of revising it.
