# TechSpec: <feature name>

> **Status:** Draft | Approved | Superseded
> **PRD:** [_prd.md](_prd.md)
> **Last updated:** <YYYY-MM-DD>

## Executive summary

One or two paragraphs. The technical approach in plain language: what we're building,
the key architectural choice, and the primary trade-off. A reader should understand
the shape of the work without reading the rest.

## System architecture

### Component overview

What the main pieces are, what each one is responsible for, and how they relate.

- **<Component A>** — purpose, boundary, what it owns.
- **<Component B>** — purpose, boundary, what it owns.
- **<Component C>** — purpose, boundary, what it owns.

Describe data flow between components in a short paragraph or a simple diagram.

### External integrations

Systems outside our control that this feature touches. For each one: what we send,
what we receive, and what happens when it's unavailable.

## Implementation design

### Core interfaces

The contracts callers will depend on. Keep code examples small (≤ 20 lines each) and
language-appropriate for the project.

```
<interface or type signature>
```

Notes on contract semantics: error conventions, idempotency, concurrency guarantees.

### Data models

Domain entities, relationships, and the request/response shapes for any APIs.

- **<Entity>** — fields, types, invariants.
- **<Entity>** — fields, types, invariants.

If the project uses a database, give the storage shape: table names, key columns,
indexes that matter.

### API surface

Endpoints, message handlers, CLI commands, library functions — whatever this feature
exposes. For each:

- **Method / path / name** — what it does.
- **Request** — shape and required fields.
- **Response** — shape and status conventions.
- **Errors** — what callers should be prepared for.

## Impact analysis

| Component | Impact | Description / risk | Required action |
|---|---|---|---|
| <existing module> | breaking / additive / behavioural | <what changes> | <migration step> |
| <existing module> | breaking / additive / behavioural | <what changes> | <migration step> |

## Testing approach

### Unit tests

- What to cover at the unit boundary.
- What to mock vs use real.
- Edge cases and error paths to exercise.

### Integration tests

- Which components are tested together.
- Test data / fixtures required.
- Environment / dependencies needed.

## Development sequencing

### Build order

Numbered, with dependencies stated.

1. <Step> — depends on nothing.
2. <Step> — depends on step 1.
3. <Step> — depends on step 2.
4. <Step> — depends on steps 1 and 3.

Each step should be independently mergeable behind a feature flag or behind unused
code paths.

### Technical dependencies

Blocking external work: infrastructure provisioning, schema migrations, third-party
contracts, package upgrades.

## Monitoring and observability

What we instrument and what we alert on.

- **Metrics**: <name, dimensions, what they tell us>
- **Logs**: <event types, structured fields>
- **Alerts**: <condition, severity, owner>

## Technical considerations

### Key decisions

Brief recap of decisions captured in ADRs. Each one links to its ADR for the full
rationale.

- **<Decision>** — chosen approach. Trade-off: <what we gave up>. See [ADR-NNN](adrs/adr-NNN.md).

### Known risks

Technical risks specific to this design. Each one names the mitigation.

- **<Risk>** — mitigation: <plan>.
- **<Risk>** — mitigation: <plan>.

## Architecture Decision Records

- [ADR-001](adrs/adr-001.md) — <title> — <one-line summary>
- [ADR-002](adrs/adr-002.md) — <title> — <one-line summary>
- [ADR-NNN](adrs/adr-NNN.md) — <title> — <one-line summary>

---

*This TechSpec describes HOW. For WHAT and WHY, see [_prd.md](_prd.md).*
