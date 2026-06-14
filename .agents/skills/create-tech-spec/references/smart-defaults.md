# Smart defaults

Fallback values for non-functional requirements. Use one **only when the user
cannot answer**, and always mark it explicitly as a **hypothesis** — in the
conversation, in the TechSpec, and in any exported data. A hypothesis must never
read like a confirmed requirement.

- **Latency** — p95 under 150 ms for synchronous APIs.
- **Availability** — 99.9% for systems facing external customers; 99.5% for
  internal systems.
- **Observability minimum** — structured logs, error metrics per endpoint,
  end-to-end distributed tracing.
- **Security minimum** — authentication, role-based authorization, audit trail
  for sensitive changes.
- **Data integrity** — critical updates (e.g., stock, balances, pricing) are
  transactional.

When offering a default, present it as a suggestion the user can accept or
override:

```
You didn't specify an availability target. Suggestion (hypothesis): 99.9%,
the usual target for customer-facing systems. Accept, or set another target?

A) Accept 99.9% (recorded as hypothesis until confirmed)
B) Stricter (99.95%+)
C) Looser (99.5%)
D) Other (please specify)
```
