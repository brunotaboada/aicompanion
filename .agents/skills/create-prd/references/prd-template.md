# PRD: <product> <feature name>

> **Version:** <version>
> **Date:** <YYYY-MM-DD>
> **Owner:** <name>
> **Status:** Draft | Approved | Superseded

## Summary

One short paragraph. What the feature is and why it is needed **now**. No
implementation details.

## Context and problem

### Target audience

- <audience 1>
- <audience 2>

### Key use cases

- <use case 1>
- <use case 2>

### Deployment context

Whether this feature lands in an **existing system** or a **new system**, and a
one-line description of that system from the product point of view. Architecture
choices belong in the TechSpec.

### Prioritized problems

What is bad, expensive, slow, insecure or fragile today. Each problem carries its
impact (with real numbers where available) and a priority.

- <problem 1 — impact: <cost / time lost / errors / customer impact>. Priority: high | medium | low>
- <problem 2 — impact: <...>. Priority: high | medium | low>

## Goals and metrics

Every goal is a goal → metric → target triplet. A goal without a metric and a
target is not done being defined.

| Goal | Metric | Target |
|---|---|---|
| <goal 1> | <metric 1> | <target 1> |
| <goal 2> | <metric 2> | <target 2> |

## Scope

### In scope

- <included item 1>
- <included item 2>

### Out of scope

Explicit list of things this feature will **not** do. Protects scope. Must not
contradict what is in scope.

- <excluded item 1>
- <excluded item 2>

## Functional requirements

One block per requirement. IDs are sequential (`FR-001`, `FR-002`, ...).

### FR-001 <requirement name>

<one-sentence description of what the system must do>

**Main flow**

- <step 1>
- <step 2>

**Alternative flows and exceptions**

- <variation / exception 1>
- <variation / exception 2>

**Expected errors**

- <error condition 1 — what the user sees / what is blocked>
- <error condition 2>

**Priority:** high | medium | low

---

### FR-002 <requirement name>

<description>

**Main flow**

- <step 1>
- <step 2>

**Alternative flows and exceptions**

- <variation / exception>

**Expected errors**

- <error condition>

**Priority:** high | medium | low

## Dependencies

Things that must happen for this feature to work. One block per dependency, typed
as `technical`, `organizational` or `external`.

### <type>: <title>

<description — who needs to deliver what, and why it blocks this feature>

### <type>: <title 2>

<description>

## Risks and mitigation

One block per risk. Multiple mitigation actions are listed as sub-items.

### <risk 1, summarized in one sentence>

- **Probability:** low | medium | high
- **Impact:** <expected impact>
- **Mitigation:**
  - <mitigation action 1>
  - <mitigation action 2>
- **Contingency plan:** <plan B if it goes wrong>

### <risk 2, summarized in one sentence>

- **Probability:** low | medium | high
- **Impact:** <expected impact>
- **Mitigation:**
  - <mitigation action 1>
- **Contingency plan:** <plan B>

## Acceptance criteria

Objective checklist that defines whether the feature is done. Avoid vague phrases
like "works well". Good example: "Every price change produces a persisted audit
record with who changed it, the previous price and a timestamp."

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

## Architecture Decision Records

References to product-strategy ADRs created during the interview. The ADR files
live in `adrs/`.

- [ADR-001](adrs/adr-001.md) — <one-line summary>

## Open questions and hypotheses

Things we couldn't decide during the interview, and every default the assistant
assumed on the user's behalf. Hypotheses must be marked as such — they must never
read like confirmed facts.

- **Hypothesis:** <assumed default> — confirm with <decision-maker / data>.
- **Open:** <question> — needs <decision-maker / research / data>.

---

*This PRD describes WHAT and WHY. For HOW (architecture, components, integrations,
non-functional targets, technical decisions and trade-offs), see the matching TechSpec.*
