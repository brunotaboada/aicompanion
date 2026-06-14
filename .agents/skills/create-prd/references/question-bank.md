# PRD interview question bank

Guide questions for the `create-prd` interview, grouped by stage. Use as a base —
always one question per turn, and skip anything already answered unambiguously by
the seed document, the user's opening summary, or the existing PRD.

Technical questions (architecture, components, communication patterns, numeric
non-functional targets) are **not** asked here — they belong to `create-tech-spec`.

## Stage 1 — Context

- What product or system does this feature belong to?
- Does this feature land in a system that already exists, or is it part of a new system?
- In two or three sentences, what is the business objective of this feature?
- Who is the target audience?
- What are the key use cases?

## Stage 2 — Pain

- What is happening today that makes this feature necessary?
- Give a recent real example with approximate numbers (cost, time lost,
  operational errors, customer impact).
- What was already tried and didn't work?

## Stage 3 — Goals and success metrics

For each objective, complete the triplet:

- What measurable result do you want to achieve?
- Which metric represents that result?
- What is the target for that metric?

## Stage 4 — Scope

- What must be ready in this delivery, no matter what?
- What is explicitly out of scope?

## Stage 5 — Functional requirements

For each requirement:

- Describe in one simple sentence what the system has to do.
- Walk me through the main flow, step by step.
- What are the common variations and exceptions?
- Under what conditions should we block the action or return an error?
- What is the priority (high, medium, low)?

## Stage 6 — Dependencies

- Does anything need to arrive from another team or area (design, commercial
  policy, legal approval, ...)?
- Does anything organizational or external need to happen before this works?

(Purely technical prerequisites go to the TechSpec.)

## Stage 7 — Risks

- What are the main risks?
- For each risk: probability, impact, mitigation, contingency plan.
- If there is more than one mitigation action, list them as sub-items.

## Stage 8 — Acceptance criteria

- List objective statements that define when the feature can be considered done.
- Avoid vague phrases like "works well".
- Good example: "Every price change produces a persisted audit record with who
  changed it, the previous price and a timestamp."

## Stage 9 — Tests and validation

- Which test types are mandatory (unit, integration, security, load, ...)?
- Which validation approach will be used (TDD, scripted manual QA, internal
  exploratory validation)?
