# Question protocol

Rules for the interactive interview session. Apply to `create-prd`, `create-tech-spec`,
and `create-tasks`.

## Cadence

- **One question per turn.** Never ask multiple questions in the same message.
  No double questions ("What is X and how about Y?").
- Wait for the user's answer before asking the next question.
- If a topic has multiple sub-questions, sequence them across turns — don't bundle.
- Use simple, direct language. Ask specific questions, not vague ones.

## Format

When the answer space is bounded, render the question as multiple choice:

```
<Question>?

A) <option>
B) <option>
C) <option>
D) Other (please specify)
```

The aicompanion runtime does not have a button-based question tool. The user replies
by typing `A`, `B`, `C`, `D`, or free text. Always include `D) Other` as the fallback.

When the answer space is genuinely open-ended, ask a plain free-form question. Use
this sparingly — bounded choices move the conversation forward faster.

Approval gates (approve / request changes / discard) may use `A/B/C` without an
`Other` option.

## When the user doesn't know

- Offer **2 or 3 plausible options** for them to choose from, grounded in what the
  interview has established so far.
- If they still can't decide, pick the most reasonable option and mark it explicitly
  as a **hypothesis** — in the conversation and in the final document. A hypothesis
  must never read like a confirmed fact.
- Never invent details the user did not give, unless offered as a suggestion clearly
  marked as a hypothesis.

## Stage checkpoints

At the end of each interview stage, post a short summary (3 to 6 lines) of what you
understood and ask whether it is correct or needs adjustment. Do not advance to the
next stage until the user confirms.

If an answer contradicts something established earlier, point out the inconsistency
immediately and ask for a correction before continuing.

## Phases by skill

### `create-prd` — nine stages

Match `question-bank.md`. Checkpoint after each stage:

1. **Context** — product/system, existing vs new system, business objective, target audience, key use cases.
2. **Pain** — what is bad, expensive, slow, insecure or fragile today; a recent real example with approximate numbers; what was already tried.
3. **Goals and metrics** — goal → metric → target triplets.
4. **Scope** — in scope, out of scope.
5. **Functional requirements** — flows, exceptions, expected errors, priority.
6. **Dependencies** — organizational and external blockers.
7. **Risks** — probability, impact, mitigation, contingency plan.
8. **Acceptance criteria** — objective, verifiable statements.
9. **Tests and validation** — mandatory test types and validation approach.

Then: consistency checks, draft, review, save.

### `create-tech-spec` — technical clarification topics

Checkpoint after each topic. Cover at minimum: architecture approach, component
boundaries, communication patterns, external integrations, data model, storage,
APIs/interfaces, non-functional requirements, given decisions, testing strategy,
observability, migration/rollout.

### `create-tasks` — breakdown approval

Present the full task table, then ask for approval or adjustments. When adjusting,
ask one clarifying question per turn.

## Strategic constraints

- **Stay in lane**: `create-prd` is product-side — no databases, frameworks, libraries,
  schemas, or APIs (those belong in the TechSpec). `create-tech-spec` is technical —
  business questions are closed by then.
- **YAGNI**: every requirement must justify its existence. "Could be useful" is not
  enough.
- **Surface uncertainty**: when the user can't answer, capture it as a hypothesis or
  in *Open questions and hypotheses* (PRD), as **(hypothesis)** in the relevant
  TechSpec section (typically *Non-functional requirements*), or in the task's
  *Open questions and hypotheses* section.

## User-side sentinels

The aicompanion shell intercepts these before they reach you:

- `/abort` — session ends; do not write any files.
- `/done` — produce the draft from what you have so far, or stop with a summary of
  what's still needed.
- `/skip` (in answer to a question) — pick a reasonable default and mark it as a
  **hypothesis** in the document section listed above for that skill.
- `/edit` — the user is opening `$EDITOR` to compose a long answer; the contents
  arrive as the next message.
