---
status: pending
title: <task title — short imperative>
type: <one of the registered types>
complexity: <low | medium | high | critical>
dependencies: []
adrs: []
---

# <Task title>

## Overview

Two or three sentences. What this task accomplishes and why it exists in the
breakdown. State which TechSpec section it implements (by name, not by quoting).

## Requirements

Numbered MUST/SHOULD statements derived from the PRD and TechSpec. Each one is
verifiable by reading the diff or running a test.

1. The change MUST `<verifiable behaviour>`.
2. The change MUST `<verifiable behaviour>`.
3. The change SHOULD `<preferred behaviour>` unless `<exception>`.

## Subtasks

3–7 checklist items. Each one describes WHAT will be true when done, not HOW to do it.

- [ ] <Outcome 1>
- [ ] <Outcome 2>
- [ ] <Outcome 3>

## Implementation details

Concrete pointers: file paths to create or modify, named integration points, the
TechSpec section that governs the design.

Refer to TechSpec section: **<section name>**.

### Relevant files

Files the implementer will read or modify to do this work.

- `<path/to/file>` — `<one-line reason>`
- `<path/to/file>` — `<one-line reason>`

### Dependent files

Files elsewhere in the codebase that will be affected by the change (callers,
schemas, tests that need updating).

- `<path/to/file>` — `<one-line reason>`

### Related ADRs

Omit this section if no ADRs apply.

- [ADR-NNN](../adrs/adr-NNN.md) — `<title>`

## Deliverables

Concrete outputs the task must produce. Include test items explicitly — code without
tests is not done.

- <Source artifact: e.g., new class / function / endpoint, with path>
- <Test artifact: e.g., unit tests covering the requirements above>
- <Doc artifact, if the PRD or TechSpec calls for one>

## Tests

Specific named test cases. Each one names the input, condition, or behaviour.
Vague entries like "test the happy path" are not acceptable.

### Unit tests

- [ ] `<test name>` — `<input / condition / expected outcome>`
- [ ] `<test name>` — `<input / condition / expected outcome>`

### Integration tests

- [ ] `<test name>` — `<scenario, end-to-end>`

## Success criteria

Measurable outcomes that prove the task is done.

- All requirements above are met.
- All tests above pass.
- `<test command>` exits 0.
- `<any feature-specific signal>`.

## Open questions and hypotheses

Optional. Things the implementer should raise rather than guess, and defaults assumed
during task breakdown that still need confirmation.

- **Hypothesis:** <assumed default> — confirm with <who can answer>.
- **Open:** <question> — needs <who can answer / research>.
