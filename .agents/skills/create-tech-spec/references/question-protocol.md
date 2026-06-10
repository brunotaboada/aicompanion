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
by typing `A`, `B`, `C`, or free text. Always include `D) Other` as the fallback.

When the answer space is genuinely open-ended, ask a plain free-form question. Use
this sparingly — bounded choices move the conversation forward faster.

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

## Phases (PRD context)

1. **Discovery** — product/system, target audience, deployment context, business objective.
2. **Pain** — what is bad, expensive, slow, insecure or fragile today. Ask for a recent
   real example with approximate numbers. Ask what was already tried and didn't work.
3. **Goals** — turn objectives into goal → metric → target triplets.
4. **Scope and requirements** — in scope, out of scope, functional requirements with
   flows, acceptance criteria, tests.
5. **Creation** — consistency checks, then generate the document. Every section
   reflects a confirmed decision or an explicitly marked hypothesis.

Don't advance a phase until the previous one is grounded and confirmed at its checkpoint.

## Strategic constraints

- **Stay in lane**: `create-prd` is product-side — no databases, frameworks, libraries,
  schemas, or APIs (those belong in the TechSpec). `create-tech-spec` is technical —
  business questions are closed by then.
- **YAGNI**: every requirement must justify its existence. "Could be useful" is not
  enough.
- **Surface uncertainty**: when the user can't answer, capture it as a hypothesis or
  in *Open Questions* with a note on what would close it.

## User-side sentinels

The aicompanion shell intercepts these before they reach you:

- `/abort` — session ends; do not write any files.
- `/done` — produce the draft from what you have so far, or stop with a summary of
  what's still needed.
- `/skip` (in answer to a question) — pick a reasonable default and mark it as a
  hypothesis.
- `/edit` — the user is opening `$EDITOR` to compose a long answer; the contents
  arrive as the next message.
