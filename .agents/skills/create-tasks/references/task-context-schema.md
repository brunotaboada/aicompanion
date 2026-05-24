# Task context schema

Definitions for the YAML frontmatter on every task file. The `create-tasks` skill
emits these and aicompanion's `run` command reads them.

## Fields

### `status` (required)

Lifecycle of the task. Set by the skill on creation; mutated later by the implementer
or by tooling.

- `pending` — created, not yet started.
- `in_progress` — actively being worked on.
- `completed` — all subtasks done, tests pass, self-review clean.
- `blocked` — waiting on a dependency or decision; explain in *Open questions*.
- `cancelled` — removed from scope after creation; preserve the file for history.

### `title` (required)

Short imperative, sentence case. "Add User entity", not "User entity addition".
The same string appears in `_tasks.md`.

### `type` (required)

One of the values from `tasks.types` in `.aicompanion.yml`, or — if that key is
absent — one of the defaults:

| Type | Use when |
|---|---|
| `frontend` | UI, client-side rendering, browser behaviour |
| `backend` | Server logic, request handling, business rules |
| `docs` | User-facing or developer-facing documentation |
| `test` | Test scaffolding, fixtures, or test-only changes |
| `infra` | Build, CI, deployment, configuration |
| `refactor` | Internal restructuring, no observable behaviour change |
| `chore` | Dependency bumps, cleanup, formatting |
| `bugfix` | Fixing an existing defect, not adding capability |

A custom registry overrides these. The skill picks the closest match.

### `complexity` (required)

Effort and risk estimate. Reassess after codebase exploration.

| Value | Criteria |
|---|---|
| `low` | Single file change, no new interfaces, straightforward logic. |
| `medium` | 2–4 files, may introduce a new interface, limited integration. |
| `high` | 5+ files, new subsystem or significant refactor, multiple integration points. |
| `critical` | Cross-cutting change, high regression risk, requires coordination. |

If a task is `high` or `critical`, consider whether it should be split.

### `dependencies` (required)

List of task numbers this one depends on. Empty list (`[]`) when none. Task numbers
are integers without padding in the YAML (`[1, 3]`), even though the filenames are
padded (`task_01.md`, `task_03.md`).

Dependencies form a DAG — circular dependencies are not allowed.

### `adrs` (optional)

List of ADR numbers this task implements. Integers without padding (`[4, 5]`).
Omit the key entirely when no ADRs apply.

## Example

```yaml
---
status: pending
title: Add POST /users endpoint
type: backend
complexity: medium
dependencies: [2]
adrs: [5]
---
```

## Numbering rules

- Task filenames are zero-padded two-digit numbers: `task_01.md`, `task_02.md`, …, `task_99.md`.
- Numbers are sequential and gap-free.
- In **update mode**, preserve existing numbers. Add new tasks at the next available number; do not renumber.
- The master `_tasks.md` lists tasks in numeric order with links to each file.
