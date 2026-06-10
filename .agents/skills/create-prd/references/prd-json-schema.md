# PRD JSON format

During the interview, keep the collected information organised internally along
this structure. **Never show the raw JSON to the user during the interview.**

After the markdown PRD is saved, if the user asks for the JSON export, write
`_prd.json` next to `_prd.md` using exactly this structure.

## Rules

- Keys are always in **English**.
- Values (the textual content) stay in the language the interview was conducted in,
  because they mirror the PRD.
- Fill in only data that was actually collected. **Do not include empty fields.**
- Do not include sections that did not appear in the final PRD.
- Do not include attachments, references, next steps, or dates/deadlines beyond `meta.date`.
- Architecture, technical decisions and non-functional requirements are **not** part
  of this JSON — they belong to the TechSpec (`create-tech-spec`).

## Structure

```json
{
  "meta": {
    "product": "",
    "feature": "",
    "prd_owner": "",
    "version": "",
    "date": "YYYY-MM-DD"
  },
  "context": {
    "summary": "",
    "target_audience": [],
    "key_use_cases": [],
    "deployment_context": {
      "type": "existing_system|new_system",
      "description": ""
    },
    "problems": [
      { "description": "", "impact": "", "priority": "high|medium|low" }
    ]
  },
  "goals": [
    { "goal": "", "metric": "", "target": "" }
  ],
  "scope": {
    "in_scope": [],
    "out_of_scope": []
  },
  "functional_requirements": [
    {
      "id": "FR-001",
      "name": "",
      "description": "",
      "main_flow": [],
      "alternative_flows": [],
      "known_errors": [],
      "priority": "high|medium|low"
    }
  ],
  "dependencies": [
    { "type": "external|organizational|technical", "title": "", "description": "" }
  ],
  "risks": [
    {
      "risk": "",
      "probability": "low|medium|high",
      "impact": "",
      "mitigation": [],
      "contingency_plan": ""
    }
  ],
  "acceptance_criteria": [],
  "testing_validation": {
    "test_types": [],
    "strategy": ""
  },
  "hypotheses": [
    { "assumption": "", "needs": "" }
  ],
  "open_questions": [
    { "question": "", "needs": "" }
  ]
}
```
