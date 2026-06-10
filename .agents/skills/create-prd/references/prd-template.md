# PRD: <feature name>

> **Status:** Draft | Approved | Superseded
> **Owner:** <name>
> **Last updated:** <YYYY-MM-DD>

## Summary

One short paragraph. What the feature is and why it is needed **now**. No
implementation details.

## Problem

The problem this feature solves, who has the problem, and what the current
state is without it.

## Business objective

The business goal behind the feature. Bulleted, measurable, time-bound where
possible. Each goal answers "what does success look like?" in business / user
terms.

- <goal 1 — e.g., "Reduce password-reset support tickets by 40% within 30 days of launch">
- <goal 2>
- <goal 3>

## Target environment

Where the feature will run and who/what it touches — platform (web, mobile,
backend service, CLI, ...), audiences, and any environment constraints that
shape the requirements. Stay product-side: no infrastructure or architecture
choices here.

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

## Acceptance criteria

Defines whether the feature is done. Each criterion is objective and verifiable.

- [criterion 1]
- [criterion 2]
- [criterion 3]

---

## Tests and validation

### Mandatory test types

- [test type 1 — e.g., unit tests for critical business rules]
- [test type 2 — e.g., integration tests for the main flow]
- [test type 3 — e.g., security test for price-change permission]

### Validation strategy

- [e.g., TDD for critical stock and pricing logic, scripted manual QA,
  exploratory validation browsing the storefront with real data]

## Risks and mitigations

User / product risks only. Technical risks belong in the TechSpec.

| Risk | Likelihood | Mitigation |
|---|---|---|
| <Adoption / behaviour / market risk> | low / med / high | <How we'll address it> |

## Architecture Decision Records

References to ADRs created during the interview. The ADR files live in `adrs/`.

- [ADR-001](adrs/adr-001.md) — <one-line summary>

## Open questions

Things we couldn't decide during the interview. Each one names what needs to happen
to close it.

- <Question> — needs <decision-maker / research / data>.

---

*This PRD describes WHAT and WHY. For HOW, see the matching TechSpec.*
