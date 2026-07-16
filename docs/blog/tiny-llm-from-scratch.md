# Build a Tiny LLM in Python (20 Words, NumPy Only)

ChatGPT feels like magic until it doesn't. It hallucinates, repeats itself, or ignores something you said three sentences ago. Most of us treat LLMs as black boxes.

They're not. Under the hood, every LLM is **next-word prediction in a loop** — tokenize, embed, attend, predict, repeat.

This post walks through a **tiny GPT with only 20 words**, in pure NumPy. One sentence — `"the cat sat"` — carries through every example. Then you'll run a **pretrained model** (weights already saved) to generate text. Inference only — no training code here.

> **Runnable code:** `examples/tiny-llm/`  
> `python3 tiny_llm_inference.py`

---

## What an LLM Actually Does

Given some text, the model guesses the next word. Then it adds that word and guesses again.

```python
words = ["the", "cat"]
next_word = model.predict(words)  # → "sat"
words.append(next_word)           # ["the", "cat", "sat"]
```

That's the whole loop behind ChatGPT. Training teaches good guesses (done once). Inference — this post — just runs the loop with frozen weights.

---

## Step 1: Turn Words Into Numbers

```python
VOCAB = ["the", "cat", "dog", "sat", "mat", "END"]
word_to_id = {w: i for i, w in enumerate(VOCAB)}

def tokenize(text):
    return [word_to_id[w] for w in text.split()]

tokenize("the cat sat")   # → [0, 1, 3]
```

Our full model uses 20 words. Same idea. `END` means stop.

---

## Step 2: Turn Numbers Into Vectors

An ID doesn't carry meaning. An **embedding** does:

```python
embeddings = {
    "the": [0.1, 0.0, 0.0],
    "cat": [0.0, 0.8, 0.2],
    "dog": [0.0, 0.7, 0.3],
    "sat": [0.2, 0.1, 0.9],
}

vectors = [embeddings[w] for w in "the cat sat".split()]
```

Similar words get similar numbers. `"cat"` ≈ `"dog"`. `"cat"` ≉ `"sat"`.

---

## Step 3: Guess the Next Word

Scores → probabilities via softmax:

```python
def softmax(scores):
    e = np.exp(scores - scores.max())
    return e / e.sum()

scores = np.array([4.0, 1.0, 0.5])  # mat, sat, dog
# → mat 93%, sat 5%, dog 3%
```

**Problem:** looking at only the last word, `"the"` gets the same guess in `"the cat..."` and `"the dog..."`. We need context.

---

## Step 4: Attention Mixes Context

Weight important words more:

```python
# "cat" matters most for what comes next
context = (
    0.1 * embeddings["the"] +
    0.7 * embeddings["cat"] +
    0.2 * embeddings["sat"]
)
```

The model learns these weights with Query / Key / Value matrices. A causal mask stops it from peeking at future words.

---

## Step 5: Load Weights and Generate

```python
model = TinyGPT.load("weights.npz")
print(model.generate("the cat and the"))
# the cat and the dog END
```

```bash
cd examples/tiny-llm
pip install -r requirements.txt
python3 tiny_llm_inference.py
```

```text
'the cat and the' -> the cat and the dog END
'the big cat sat on the' -> the big cat sat on the big mat END
'the red big cat sat on the' -> the red big cat sat on the big mat END
'the small dog ran to the small' -> the small dog ran to the small house END
```

---

## Inference Code (~70 lines)

The whole model is: **embed → attend → predict**. No multi-head, no feed-forward net, no GELU.

```python
"""Tiny GPT inference — load weights.npz and generate."""

import argparse
from pathlib import Path
import numpy as np

VOCAB = [
    "the", "cat", "dog", "sat", "ran", "on", "mat", "house", "a",
    "big", "small", "quickly", "slowly", "and", "is", "red", "blue", "to",
    "PAD", "END",
]
WORD_TO_ID = {w: i for i, w in enumerate(VOCAB)}
ID_TO_WORD = {i: w for i, w in enumerate(VOCAB)}
END = WORD_TO_ID["END"]


def softmax(x):
    e = np.exp(x - x.max(axis=-1, keepdims=True))
    return e / e.sum(axis=-1, keepdims=True)


def attention(x, Wq, Wk, Wv):
    """Single-head attention. x: (n_words, d)"""
    Q, K, V = x @ Wq, x @ Wk, x @ Wv
    n = len(x)
    mask = np.triu(np.full((n, n), -1e9), 1)  # no looking ahead
    weights = softmax(Q @ K.T / np.sqrt(x.shape[1]) + mask)
    return weights @ V


class TinyGPT:
    def __init__(self, w):
        self.w = w

    @classmethod
    def load(cls, path):
        return cls(dict(np.load(path)))

    def forward(self, ids):
        w = self.w
        x = w["emb"][ids] + w["pos"][: len(ids)]   # embed + position
        x = x + attention(x, w["Wq"], w["Wk"], w["Wv"])  # mix context
        return x @ w["head"]                       # score each vocab word

    def generate(self, prompt, max_new=8):
        ids = [WORD_TO_ID[t] for t in prompt.lower().split()]
        for _ in range(max_new):
            next_id = int(self.forward(np.array(ids)).argmax(-1)[-1])
            ids.append(next_id)
            if next_id == END:
                break
        return " ".join(ID_TO_WORD[i] for i in ids)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", default="weights.npz")
    parser.add_argument("--prompt", default="the cat and the")
    args = parser.parse_args()
    print(TinyGPT.load(args.weights).generate(args.prompt))
```

Optional retrain: `python3 train.py --out weights.npz`

---

## Tiny GPT vs GPT-4

| | This post | GPT-4 |
|--|--|--|
| Words | 20 | ~100,000 |
| Attention | 1 head | many heads |
| Layers | 1 | 120+ |
| Inference | load `.npz` → generate | same idea |

Same idea. Different scale.

---

## Why Bother?

- **Hallucination** — predicts plausible words, not facts
- **Temperature** — more randomness in word picking
- **Context limits** — longer text = more computation
- **Token billing** — you pay per token, not per word

Training vs inference matches ChatGPT: learning already happened; you're running a frozen model.

---

## Next Steps

- Code + weights: `examples/tiny-llm/`
- Try: `python3 tiny_llm_inference.py --prompt "the dog and the"`
- Paper: [Attention Is All You Need](https://arxiv.org/abs/1706.03762)

LLMs aren't magic. They're a loop that predicts the next word. Load a tiny one, and the black box disappears.
