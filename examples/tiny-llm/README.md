# Tiny LLM — NumPy from scratch

A minimal GPT-style language model with a 20-word vocabulary. Educational companion to [`docs/blog/tiny-llm-from-scratch.md`](../../docs/blog/tiny-llm-from-scratch.md).

## Quick start

```bash
cd examples/tiny-llm
pip install -r requirements.txt
python3 step_by_step.py   # softmax, embeddings, attention demos
python3 train.py          # train ~10s on CPU, then generate
```

## What it includes

| File | Purpose |
|------|---------|
| `tiny_llm/vocab.py` | 20-word vocabulary + tokenization |
| `tiny_llm/layers.py` | softmax, attention, layer norm, FFN |
| `tiny_llm/model.py` | full transformer block + train/generate |
| `tiny_llm/data.py` | training sentences |
| `step_by_step.py` | small building-block demos |
| `train.py` | train and run generation prompts |

## Architecture

- Token embeddings + sinusoidal positional encoding
- 1 transformer block: multi-head attention (4 heads) → layer norm → residual → FFN → layer norm → residual
- LM head → softmax → next-token prediction
- Causal masking (no peeking at future tokens)
- Pure NumPy forward + backward pass

## Expected output (after training)

```
'the red big cat sat on the' -> the red big cat sat on the big mat END
'the cat and the'            -> the cat and the dog END
'the small dog ran to the small' -> the small dog ran to the small house END
```

Results vary slightly by seed and epoch count. Train longer (`--epochs 800`) for more stable outputs.

## Requirements

- Python 3.10+
- NumPy
