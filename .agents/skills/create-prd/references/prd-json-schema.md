# PRD JSON format

When the user asks for the PRD in structured JSON, write `_prd.json` next to
`_prd.md` using this shape. All keys are in English, regardless of the language
the interview was conducted in. Omit a key only when the matching PRD section
is genuinely empty.

```json
{
  "feature_name": "string",
  "status": "draft | approved | superseded",
  "owner": "string",
  "last_updated": "YYYY-MM-DD",
  "summary": "string — what the feature is and why it is needed now",
  "problem": "string — the problem, who has it, current state",
  "business_objectives": ["string — measurable goal"],
  "target_environment": "string — where the feature runs and for whom",
  "user_stories": [
    { "persona": "string", "action": "string", "outcome": "string" }
  ],
  "core_features": [
    { "name": "string", "description": "string", "user_value": "string" }
  ],
  "user_experience": {
    "primary_flow": ["string — step"],
    "alternate_flows": ["string"],
    "error_states": ["string"]
  },
  "non_goals": ["string"],
  "acceptance_criteria": ["string — objective, verifiable criterion"],
  "tests_and_validation": {
    "mandatory_test_types": ["string — e.g., unit tests for critical business rules"],
    "validation_strategy": "string — e.g., TDD for critical logic, scripted manual QA"
  },
  "risks": [
    { "risk": "string", "likelihood": "low | medium | high", "mitigation": "string" }
  ],
  "adrs": [
    { "id": "ADR-001", "path": "adrs/adr-001.md", "summary": "string" }
  ],
  "open_questions": [
    { "question": "string", "needs": "string — decision-maker / research / data" }
  ]
}
```
