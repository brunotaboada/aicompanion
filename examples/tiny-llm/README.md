# Tiny LLM — NumPy from scratch

Minimal GPT: **embed → single-head attention → predict**. Companion to [`docs/blog/tiny-llm-from-scratch.md`](../../docs/blog/tiny-llm-from-scratch.md).

## Quick start (inference)

```bash
cd examples/tiny-llm
pip install -r requirements.txt
python3 tiny_llm_inference.py
```

## Retrain (optional)

```bash
python3 train.py --epochs 800 --out weights.npz
```

## Files

| File | Purpose |
|------|---------|
| `weights.npz` | pretrained weights |
| `tiny_llm_inference.py` | self-contained inference (~70 lines) |
| `train.py` | train once, save weights |
| `infer.py` | package-based inference |
| `step_by_step.py` | tiny concept demos |

## Expected output

```text
'the cat and the' -> the cat and the dog END
'the big cat sat on the' -> the big cat sat on the big mat END
'the red big cat sat on the' -> the red big cat sat on the big mat END
'the small dog ran to the small' -> the small dog ran to the small house END
```
