# PRD: Tiny GPT Educational Demo

> **Version:** 0.1
> **Date:** 2026-07-19
> **Owner:** Bruno Taboada
> **Status:** Draft

## Summary

Build a personal educational tiny GPT: a small English transformer that can be
**trained from scratch** and used for multi-word text generation, in the same
spirit as the AlgoMonster course demo (tiny model, tiny vocab, transparent
forward pass). Needed now so the learner owns both the inference path and a
custom-trained weight file, instead of depending on a course-hosted
`tiny_english_gpt.npz`.

## Context and problem

### Target audience

- The feature owner (personal learning — understand transformers by building one)

### Key use cases

- Train a tiny English language model on a small hand-crafted corpus
- Run autoregressive multi-word generation from a prompt using the full
  transformer stack (attention, FFN, layer norm, residuals, causal mask)
- Inspect step-by-step next-token probabilities to see long-range / selective
  attention behaviour (similar to the AlgoMonster demo prompts)

### Deployment context

**New system.** A standalone educational project (not part of aicompanion, not
Java). The planning artifacts live under `features/tiny-gpt/` in this repo;
implementation is a separate Python project (language confirmed for TechSpec).

### Prioritized problems

- **No owned trained weights** — the course model path
  (`/models/tiny_english_gpt.npz`) is not available for reuse. Impact: cannot
  run or extend the demo without building training. Priority: high
- **Opaque “black box” learning** — using only a prebuilt model hides how
  training produces the behaviours shown in demos. Impact: weaker educational
  outcome. Priority: high
- **Hypothesis:** No prior local attempt at training this specific tiny model —
  confirm if anything was already tried. Priority: medium

## Goals and metrics

| Goal | Metric | Target |
|---|---|---|
| Own a train→infer loop | End-to-end scripted path from corpus → weight file → generation | One successful train + generate run on a laptop/CPU |
| Reproduce educational demos | Demo prompts produce coherent completions with inspectable top-k probs | At least 3 demo scenarios (long-range, selective, pattern) run without crash |
| Understand transformer pieces | Each major block is present and exercised in the forward pass | Attention, FFN, layer norm, residual, causal mask all used in generation |
| **(Hypothesis)** Keep scope tiny | Vocab size and model size stay “course-scale” | Vocab ≤ ~50 words; model fits comfortably in memory on CPU |

## Scope

### In scope

- Tiny English vocabulary and hand-crafted training corpus
- Training that produces a weight file owned by the project
- Full transformer forward pass for inference (multi-head attention, FFN, norms,
  residuals, causal masking)
- Autoregressive generation (greedy and/or temperature sampling)
- Educational demos that print next-word probabilities

### Out of scope

- Production LLM serving, APIs, or web UI
- Integration with aicompanion or any Java code
- Large-scale datasets, GPU clusters, or SOTA accuracy
- Multilingual models, chat alignment, tool use
- Matching AlgoMonster’s exact proprietary weights byte-for-byte

## Functional requirements

### FR-001 Train custom tiny model

The system must train a tiny GPT on a small English corpus and save weights the
learner owns.

**Main flow**

- Prepare vocabulary and training examples
- Run training for a configured number of steps/epochs
- Save weights to a project-owned file (e.g. `.npz` or equivalent)

**Alternative flows and exceptions**

- Resume or re-run training from scratch when the corpus changes
  **(hypothesis:** always retrain from scratch in v1)

**Expected errors**

- Empty or invalid corpus — training refuses to start with a clear message
- Missing required config — training aborts with what is missing

**Priority:** high

---

### FR-002 Generate text with full transformer

The system must load saved weights and generate multiple words from a text prompt
using the full transformer forward pass.

**Main flow**

- Load weights and vocabulary
- Tokenize prompt (words in vocab)
- For each generation step, run the transformer, pick next token, append

**Alternative flows and exceptions**

- Greedy decoding (temperature 0) vs sampling with temperature
- Stop early when an END token is produced **(hypothesis:** END token exists)

**Expected errors**

- Prompt with no recognized words — report that nothing was tokenized
- Missing weight file — refuse to generate with a clear message

**Priority:** high

---

### FR-003 Educational demo scenarios

The system must run a small set of demo prompts that illustrate attention /
context behaviour and print top probability candidates per step.

**Main flow**

- Run fixed demo prompts (e.g. long-range size word, selective attention,
  pattern completion)
- Print step-by-step next-word and top probabilities
- Print final completed string

**Alternative flows and exceptions**

- **(Hypothesis)** Demos are a script entry point, not an interactive REPL in v1

**Expected errors**

- Demo fails if weights were never trained — instruct user to train first

**Priority:** medium

## Dependencies

### external: Training data ownership

A small English corpus and vocabulary must be authored or adapted for the demo
(no dependency on AlgoMonster-hosted model files).

### organizational: None

Single learner / owner; no cross-team deliveries required.

## Risks and mitigation

### Custom model may not reproduce AlgoMonster demo behaviours

- **Probability:** medium
- **Impact:** Educational demos feel less convincing
- **Mitigation:**
  - Design corpus to encode the intended patterns (size→destination, etc.)
  - Keep model tiny and overfit deliberately for demos
- **Contingency plan:** Adjust corpus and retrain; document which demos are
  reliable vs illustrative

### Scope creep toward a “real” LLM

- **Probability:** medium
- **Impact:** Project never finishes as a clear learning artifact
- **Mitigation:**
  - Hard out-of-scope list above
  - Cap vocab and layer count in TechSpec
- **Contingency plan:** Freeze architecture; ship train+demo only

## Acceptance criteria

- [ ] Running training produces a project-owned weight file without external
      course model downloads
- [ ] Loading those weights supports multi-word autoregressive generation
- [ ] Forward pass includes multi-head attention, causal mask, feed-forward,
      layer norm, and residual connections
- [ ] At least three demo prompts print per-step top probabilities and a final
      completion
- [ ] No aicompanion / Java runtime is required to train or generate

---

## Tests and validation

### Mandatory test types

- Unit tests for tokenizer/vocab mapping and core numeric blocks (softmax,
  attention shapes, causal mask)
- Integration test: train briefly (or load fixture weights) → generate at least
  one token without error
- **(Hypothesis)** Manual scripted run of the educational demos after training

### Validation strategy

- Automated tests for math/shape correctness
- Manual educational validation: run demos and confirm outputs are inspectable
  and pattern-aligned enough for learning (not SOTA quality)

## Architecture Decision Records

None yet (PRD interview abbreviated; ADRs expected from TechSpec).

## Open questions and hypotheses

- **Hypothesis:** Vocab stays course-scale (roughly dozens of words), not a
  general English tokenizer.
- **Hypothesis:** Weight format is NumPy `.npz` (or similar) for easy
  inspection, matching the course demo style.
- **Hypothesis:** v1 is CLI/scripts only — no notebook requirement, no web UI.
- **Hypothesis:** END token is part of the vocabulary and stops generation.
- **Hypothesis:** No prior failed attempt exists that we must avoid repeating.
- **Open:** Exact demo corpus / sentences to encode (long-range “big”, size-
  matched destinations, etc.) — decide in TechSpec or first implementation task.
- **Open:** Whether training uses pure NumPy or a small ML framework — TechSpec.

---

*This PRD describes WHAT and WHY. For HOW (architecture, components, integrations,
non-functional targets, technical decisions and trade-offs), see the matching TechSpec.*
