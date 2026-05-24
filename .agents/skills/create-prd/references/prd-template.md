# PRD: <feature name>

> **Status:** Draft | Approved | Superseded
> **Owner:** <name>
> **Last updated:** <YYYY-MM-DD>

## Overview

One short paragraph. The problem this feature solves, who has the problem, and why
solving it now is worth the effort. No implementation details.

## Goals

Bulleted, measurable, time-bound where possible. Each goal answers "what does success
look like?" in business / user terms.

- <goal 1 — e.g., "Reduce password-reset support tickets by 40% within 30 days of launch">
- <goal 2>
- <goal 3>

## User stories

Persona-driven. Each story is *who*, *what*, *why*.

- As a `<persona>`, I want to `<action>` so that `<outcome>`.
- As a `<persona>`, I want to `<action>` so that `<outcome>`.

## Core features

Prioritised list. Each feature: name, one-line description, why it matters. No HOW.

1. **<Feature name>** — <one-line description>. Why: <user value>.
2. **<Feature name>** — <one-line description>. Why: <user value>.

## User experience

User journeys at the level of *steps the user takes*, not screens or APIs.

- **Primary flow**: `<persona>` does `<step 1>` → `<step 2>` → `<step 3>` → outcome.
- **Alternate flow**: <when the primary flow doesn't apply>.
- **Error states**: what the user sees when things go wrong.

Call out accessibility, internationalisation, and onboarding considerations if they
shape the requirements.

## Non-goals (out of scope)

Explicit list of things this feature will **not** do. Protects scope.

- <Non-goal 1>
- <Non-goal 2>

## Phased rollout plan

- **MVP** — minimum to validate the core hypothesis. Success criterion: <metric>.
- **Phase 2** — <next slice>. Success criterion: <metric>.
- **Phase 3** — <next slice>. Success criterion: <metric>.

Each phase ships independently and is usable on its own.

## Success metrics

How we'll know the feature worked. Tie back to the *Goals* section.

- **Engagement**: <metric and target>
- **Quality**: <metric and target>
- **Business impact**: <metric and target>

## Risks and mitigations

User / product risks only. Technical risks belong in the TechSpec.

| Risk | Likelihood | Mitigation |
|---|---|---|
| <Adoption / behaviour / market risk> | low / med / high | <How we'll address it> |

## Architecture Decision Records

References to ADRs created during the brainstorm. The ADR files live in `adrs/`.

- [ADR-001](adrs/adr-001.md) — <one-line summary>
- [ADR-002](adrs/adr-002.md) — <one-line summary>

## Open questions

Things we couldn't decide during the brainstorm. Each one names what needs to happen
to close it.

- <Question> — needs <decision-maker / research / data>.
- <Question> — needs <decision-maker / research / data>.

---

*This PRD describes WHAT and WHY. For HOW, see the matching TechSpec.*
