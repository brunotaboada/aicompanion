# Question protocol

Rules for the interactive brainstorming session. Apply to `create-prd`, `create-tech-spec`,
and `create-tasks`.

## Cadence

- **One question per turn.** Never ask multiple questions in the same message.
- Wait for the user's answer before asking the next question.
- If a topic has multiple sub-questions, sequence them across turns — don't bundle.

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
by typing `A`, `B`, `C`, or free text. Always include `D) Other` as the fallback.

When the answer space is genuinely open-ended, ask a plain free-form question. Use
this sparingly — bounded choices move the conversation forward faster.

## Phases (PRD context)

1. **Discovery** — who has the problem, what is the problem, what's the current state.
2. **Understanding** — specific needs, business rationale, hard constraints.
3. **Options** — 2–3 distinct approaches with trade-offs. User picks one.
4. **Refinement** — scope boundaries, phasing, success metrics.
5. **Creation** — generate the document. Every section reflects a confirmed decision.

Don't advance to *Options* until *Understanding* is grounded. Don't advance to
*Refinement* until the user has picked an approach.

## Strategic constraints

- **Stay product-side**: no databases, frameworks, libraries, schemas, or APIs. Those
  belong in the TechSpec phase.
- **YAGNI**: every requirement must justify its existence. "Could be useful" is not
  enough.
- **Surface uncertainty**: when the user can't answer, capture it in *Open Questions*
  with a note on what would close it.

## User-side sentinels

The aicompanion shell intercepts these before they reach you:

- `/abort` — session ends; do not write any files.
- `/done` — produce the draft from what you have so far, or stop with a summary of
  what's still needed.
- `/skip` (in answer to a question) — pick a reasonable default and note the choice
  in *Open Questions*.
- `/edit` — the user is opening `$EDITOR` to compose a long answer; the contents
  arrive as the next message.
