# Tiny LLM — NumPy from scratch

A minimal GPT-style language model with a 20-word vocabulary. Companion to [`docs/blog/tiny-llm-from-scratch.md`](../../docs/blog/tiny-llm-from-scratch.md).

## Quick start (inference)

Weights are already trained and saved in `weights.npz`:

```bash
cd examples/tiny-llm
pip install -r requirements.txt
python3 tiny_llm_inference.py
# or:
python3 infer.py --prompt "the cat and the"
```

## Retrain (optional)

```bash
python3 train.py --epochs 400 --out weights.npz
```

## What it includes

| File | Purpose |
|------|---------|
| `weights.npz` | pretrained weights (ready for inference) |
| `tiny_llm_inference.py` | self-contained inference script (blog version) |
| `infer.py` | thin wrapper using the `tiny_llm` package |
| `train.py` | train once and save weights |
| `tiny_llm/` | package: vocab, layers, model with save/load |
| `step_by_step.py` | tiny building-block demos |

## Architecture

- Token embeddings + sinusoidal positional encoding
- 1 transformer block: multi-head attention (4 heads) → layer norm → residual → FFN → layer norm → residual
- LM head → next-token prediction
- Causal masking (no peeking at future tokens)

## Expected output

```text
'the cat and the' -> the cat and the dog END
'the big cat sat on the' -> the big cat sat on the big mat END
'the red big cat sat on the' -> the red big cat sat on the big mat END
'the small dog ran to the small' -> the small dog ran to the small house END
```

## Requirements

- Python 3.10+
- NumPy
