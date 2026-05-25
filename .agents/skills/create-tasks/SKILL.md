---
description: Decomposes a PRD and TechSpec into independently implementable task files for `run`.
output: _tasks.md
---

# create-tasks

You are running the `create-tasks` skill. Decompose the PRD and TechSpec for the
feature `{{feature}}` into independently implementable task files that aicompanion's
`run` command will execute one by one.

## Inputs

- Feature name: `{{feature}}`
- Feature directory: `{{feature_dir}}`
- PRD (read first): `{{feature_dir}}_prd.md`
- TechSpec (preferred): `{{feature_dir}}_techspec.md`
- Seed document (optional): `{{seed_file}}`
- Update mode: `{{update_mode}}` — when `yes`, task files already exist and must be revised, not replaced.

## Outputs

- Master task list → `{{feature_dir}}_tasks.md`
- Individual task files → `{{feature_dir}}tasks/task_NN.md` (zero-padded, two digits, sequential from `task_01.md`)

## References (read on demand)

- Task template: `{{templates_dir}}task-template.md`
- Task metadata schema: `{{templates_dir}}task-context-schema.md`
- Question protocol: `{{templates_dir}}question-protocol.md` (shared rules)

## Hard rules

1. **Do not write any task files until the user has approved the breakdown.**
2. **Every task must be independently implementable** when its declared dependencies are met. No circular dependencies.
3. **No mega-tasks.** A task that touches more than 7 files or has more than 7 subtasks must be split.
4. **Do not duplicate the TechSpec.** Reference the TechSpec section by name; never copy interface definitions, code snippets, or diagrams into task files.
5. **Specific test cases.** "Test the happy path" is not a test case. Each test must name the input, condition, or behaviour being verified.
6. **One question per turn**, multiple-choice `A/B/C/D` when bounded.
7. **Update mode** (`{{update_mode}} == yes`): read existing task files first. Add or revise — do not renumber or delete tasks the user has not asked to remove.

## Workflow

### Phase 1 — Load type registry

Read `.aicompanion.yml` if it exists. If it defines `tasks.types`, use that list as the
allowed `type` values for task frontmatter. Otherwise fall back to:

```
frontend, backend, docs, test, infra, refactor, chore, bugfix
```

### Phase 2 — Ground

Read the following (in parallel if your runtime supports it):

- `{{feature_dir}}_prd.md`
- `{{feature_dir}}_techspec.md` if it exists
- `{{seed_file}}` if it exists
- Any existing ADRs in `{{adr_dir}}`
- The task template at `{{templates_dir}}task-template.md`
- The schema at `{{templates_dir}}task-context-schema.md`
- Any existing task files in `{{feature_dir}}tasks/`

**Missing PRD and TechSpec**: stop and ask the user to run `create-prd {{feature}}` first.

**Missing TechSpec only**: warn the user. Derive tasks from PRD functional requirements
plus codebase exploration. Mark each task's `<requirements>` block as PRD-derived and
call out the implementation gaps in *Open questions*.

### Phase 3 — Decompose

Break the work into granular, ordered tasks. Each task captures:

- **title** — short imperative ("Add User entity", not "User entity addition")
- **type** — one of the registered types from Phase 1
- **complexity** — one of:
  - `low` — single file change, no new interfaces, straightforward logic.
  - `medium` — 2–4 files, may introduce a new interface, limited integration.
  - `high` — 5+ files, new subsystem or significant refactor, multiple integration points.
  - `critical` — cross-cutting change, high regression risk, requires coordination.
- **dependencies** — list of prior task numbers this one depends on (empty list if none).
- **adrs** — list of ADR numbers this task implements (omit if none).

Embed test requirements in every task. Map each task to specific TechSpec sections by
name (not by quoting them).

### Phase 4 — Present for approval

Show the full breakdown as a table:

```
#   Title                         Type     Complexity  Depends on   ADRs
01  Create User entity            backend  low         —            004
02  Add UserRepository            backend  low         01           004
03  Wire DI for UserRepository    infra    low         02           —
04  Add POST /users endpoint      backend  medium      02           005
05  Add GET /users/{id} endpoint  backend  low         02           005
06  Add integration tests         test     medium      04, 05       —
```

Ask:

```
A) Approve and generate files
B) Adjust the breakdown
C) Discard and stop
```

- `A` → proceed to Phase 5.
- `B` → ask what to change, revise, present again.
- `C` → stop without writing.

### Phase 5 — Generate task files

Write `{{feature_dir}}_tasks.md` as a master list using the same table format as Phase 4
plus a link column to each task file.

For each task, write `{{feature_dir}}tasks/task_NN.md` (zero-padded two-digit number).
Each file starts with YAML frontmatter and follows the structure in
`{{templates_dir}}task-template.md`:

```yaml
---
status: pending
title: <task title>
type: <type>
complexity: <low|medium|high|critical>
dependencies: [<task numbers, or []>]
adrs: [<adr numbers, or omit>]
---
```

Numbering must be sequential and consistent between `_tasks.md` and the task files.

### Phase 6 — Enrich each task file

For each task file just written (or in update mode, each file missing required
sections), fill in all template sections:

- `## Overview` — what the task accomplishes and why (2–3 sentences).
- `## Requirements` — numbered MUST/SHOULD statements derived from PRD + TechSpec.
- `## Subtasks` — 3–7 checklist items describing WHAT, not HOW.
- `## Implementation details` — file paths to create / modify, named integration points.
  - `### Relevant files` — discovered paths with one-line reasons.
  - `### Dependent files` — files affected by the change with one-line reasons.
  - `### Related ADRs` — links to ADRs or omit if none apply.
- `## Deliverables` — concrete outputs the task must produce, including test items.
- `## Tests` — specific named test cases (unit and integration), each one a checklist line.
- `## Success criteria` — measurable outcomes including "all tests pass".

Reassess complexity based on what exploration revealed. If the reality is harsher,
update the frontmatter and notify the user in the final summary.

If enrichment fails for one task, continue with the rest and report all failures at the end.

### Phase 7 — Confirm

Print a one-paragraph summary:

- Number of tasks written.
- Any complexity reclassifications.
- Any enrichment failures.

Tell the user:

> Done. Review the tasks, then run `run --features features/{{feature}}` to execute them.

## Stop signals

- User types `/abort` → stop immediately. Do not write any files.
- User types `/done` → if the breakdown is approved, generate files and stop. Otherwise stop with a summary of where the breakdown stands.
- User types `/skip` in answer to a question → pick a reasonable default and note the choice in the relevant task's *Open questions* sub-section.

## Anti-patterns

- **Mega-tasks** — > 7 files or > 7 subtasks → split.
- **TechSpec duplication** — reference by section name, never copy.
- **Vague test descriptions** — name the specific input / condition / behaviour.
- **Renumbering in update mode** — preserve numbers of unchanged tasks.
- **HOW in subtasks** — subtasks describe what must be true when done, not the keystrokes to get there.
