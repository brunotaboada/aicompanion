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

### Communication patterns

How components talk to each other: synchronous, asynchronous, or both. Queues,
messaging, streaming, caching — and why that pattern fits.

## Non-functional requirements

Numeric targets or clear standards per category. Defaults assumed on the user's
behalf are marked **(hypothesis)** — see `smart-defaults.md`. Include at least
performance and availability, even if marked as hypothesis.

- **Performance** — <e.g., p95 under 150 ms for synchronous endpoints>
- **Availability** — <e.g., 99.9% monthly uptime in production>
- **Security and authorization** — <e.g., mandatory authentication, role-based access, audit of sensitive changes>
- **Observability** — <e.g., structured logs, error metrics per endpoint, end-to-end distributed tracing>
- **Reliability and data integrity** — <e.g., stock updates are transactional>
- **Compatibility and portability** — <e.g., versioned REST JSON API under /v1, OCI container image>
- **Compliance** — <e.g., price and stock audit trail available for reconciliation>
- **Accessibility (consuming frontend)** — <e.g., API responses carry alt text and labels needed for accessibility>

Drop categories that genuinely don't apply; don't pad.

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

### Key decisions and trade-offs

Every significant decision carries its justification and its trade-off. Decisions
that were already given (not made in this interview) are recorded the same way.
Each one links to its ADR for the full rationale.

- **<Decision>** — justification: <why this was decided>. Trade-off: <cost or
  limitation accepted>. See [ADR-NNN](adrs/adr-NNN.md).

### Known risks

Technical risks specific to this design. Each one names the mitigation.

- **<Risk>** — mitigation: <plan>.
- **<Risk>** — mitigation: <plan>.

## Architecture Decision Records

Continue numbering from PRD-era ADRs (e.g. if the PRD produced ADR-001–003, the first
TechSpec ADR is ADR-004). List every ADR for this feature:

- [ADR-NNN](adrs/adr-NNN.md) — <title> — <one-line summary>

---

*This TechSpec describes HOW. For WHAT and WHY, see [_prd.md](_prd.md).*
