# Build a Tiny LLM in Python (20 Words, NumPy Only)

ChatGPT feels like magic until it doesn't. It hallucinates, repeats itself, or ignores something you said three sentences ago. Most of us treat LLMs as black boxes.

They're not. Under the hood, every LLM is **next-word prediction in a loop** — tokenize, embed, attend, predict, repeat.

This post walks through a **tiny GPT with only 20 words**, in pure NumPy. We'll use one sentence — `"the cat sat"` — so every example builds on the last. Then you'll run a **pretrained model** (weights already trained and saved) to generate text. No training code in this post — just inference.

> **Runnable code:** `examples/tiny-llm/`  
> `python3 tiny_llm_inference.py`

---

## What an LLM Actually Does

Given some text, the model guesses the next word. Then it adds that word and guesses again.

```python
words = ["the", "cat"]          # input so far
next_word = model.predict(words) # → "sat"
words.append(next_word)          # ["the", "cat", "sat"]
next_word = model.predict(words) # → "on" (or END to stop)
```

That's the whole loop behind ChatGPT. Training teaches the model good guesses (done once, offline). Inference — what this post focuses on — just runs the loop with frozen weights.

---

## Step 1: Turn Words Into Numbers

Computers need numbers, not words. First we give each word an ID:

```python
VOCAB = ["the", "cat", "dog", "sat", "mat", "END"]  # simplified
word_to_id = {w: i for i, w in enumerate(VOCAB)}

def tokenize(text):
    return [word_to_id[w] for w in text.split()]

tokenize("the cat sat")   # → [0, 1, 3]
```

Our full model uses 20 words. Same idea, bigger list. `END` means "stop generating."

---

## Step 2: Turn Numbers Into Vectors

An ID like `1` doesn't carry meaning. An **embedding** is a short list of numbers that does:

```python
import numpy as np

# Each word → 3 numbers (real models use hundreds)
embeddings = {
    "the": [0.1, 0.0, 0.0],
    "cat": [0.0, 0.8, 0.2],
    "dog": [0.0, 0.7, 0.3],
    "sat": [0.2, 0.1, 0.9],
    "mat": [0.3, 0.1, 0.8],
}

sentence = "the cat sat"
vectors = np.array([embeddings[w] for w in sentence.split()])
# shape: (3, 3) — three words, three numbers each
```

After training, similar words get similar numbers. `"cat"` and `"dog"` end up close. `"cat"` and `"mat"` end up farther apart.

---

## Step 3: Guess the Next Word

Multiply the last word's vector by a weight matrix. You get a score for each word in the vocabulary. Softmax turns scores into probabilities:

```python
def softmax(scores):
    e = np.exp(scores - scores.max())
    return e / e.sum()

# Scores for [mat, sat, dog] after seeing "the cat"
scores = np.array([4.0, 1.0, 0.5])
probs = softmax(scores)
# mat: 93%, sat: 6%, dog: 1%
```

**The problem:** this only looks at one word. `"the"` gets the same guess whether the sentence is `"the cat..."` or `"the dog..."`. We need context.

---

## Step 4: Mix in Context With Attention

Instead of using just the last word, **combine all the words** — but not equally. `"cat"` should matter more than `"the"`.

```python
# Hand-picked weights for "the cat sat" → predict next word
weights = np.array([0.1, 0.7, 0.2])   # the, cat, sat

context = (
    0.1 * embeddings["the"] +
    0.7 * embeddings["cat"] +
    0.2 * embeddings["sat"]
)
# context ≈ mostly "cat" → good guess for what comes next
```

Attention **learns** these weights automatically. Each word asks "who is relevant to me?" via Query / Key / Value vectors. Causal masking stops the model from peeking at future words.

---

## Step 5: Wrap It in a Transformer Block

Real models add a few helpers around attention:

```python
x = embeddings_for_sentence

x = x + attention(x)    # mix in context, keep original info
x = layer_norm(x)       # keep numbers in a sane range
scores = x[-1] @ lm_head  # last word → guess next word
```

- `x + attention(x)` — a **residual connection**; don't throw away the original
- `layer_norm` — stops numbers from growing out of control
- `x[-1]` — use the **last position** to predict the next word

GPT-4 stacks dozens of these blocks. Ours uses one. Same pattern, bigger scale.

---

## Step 6: Load Pretrained Weights and Generate

Training already happened. The weights live in `weights.npz`. Inference is just: load → tokenize → forward → pick next word → repeat.

```python
model = TinyGPT.load("weights.npz")

words = ["the", "cat", "and", "the"]
while words[-1] != "END":
    words.append(model.predict(words))

print(" ".join(words))
# the cat and the dog END
```

Run it:

```bash
cd examples/tiny-llm
pip install -r requirements.txt
python3 tiny_llm_inference.py
```

Expected output:

```text
'the cat and the' -> the cat and the dog END
'the big cat sat on the' -> the big cat sat on the big mat END
'the red big cat sat on the' -> the red big cat sat on the big mat END
'the small dog ran to the small' -> the small dog ran to the small house END
```

---

## Inference Code (No Training)

Save as `tiny_llm_inference.py` (or use the copy in `examples/tiny-llm/`). This is **forward pass only** — no backpropagation.

```python
"""Tiny GPT inference — load weights.npz and generate text."""

from __future__ import annotations

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
END_ID = WORD_TO_ID["END"]


def tokenize(text: str) -> list[int]:
    return [WORD_TO_ID[w] for w in text.lower().split()]


def detokenize(ids: list[int]) -> str:
    return " ".join(ID_TO_WORD[i] for i in ids)


def softmax(x, axis=-1):
    x = x - np.max(x, axis=axis, keepdims=True)
    e = np.exp(x)
    return e / np.sum(e, axis=axis, keepdims=True)


def gelu(x):
    return 0.5 * x * (1.0 + np.tanh(np.sqrt(2.0 / np.pi) * (x + 0.044715 * x**3)))


def layer_norm(x, gamma, beta, eps=1e-5):
    mean = x.mean(axis=-1, keepdims=True)
    var = ((x - mean) ** 2).mean(axis=-1, keepdims=True)
    return gamma * (x - mean) / np.sqrt(var + eps) + beta


def positional_encoding(seq_len, d_model):
    position = np.arange(seq_len)[:, None]
    div = np.exp(np.arange(0, d_model, 2) * (-np.log(10000.0) / d_model))
    pe = np.zeros((seq_len, d_model))
    pe[:, 0::2] = np.sin(position * div)
    pe[:, 1::2] = np.cos(position * div)
    return pe


def causal_mask(seq_len):
    return np.triu(np.ones((seq_len, seq_len)), k=1) * -1e9


def attention(x, w_q, w_k, w_v, w_o, n_heads):
    batch, seq_len, d_model = x.shape
    d_head = d_model // n_heads

    q = (x @ w_q).reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)
    k = (x @ w_k).reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)
    v = (x @ w_v).reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)

    scores = q @ k.transpose(0, 1, 3, 2) / np.sqrt(d_head) + causal_mask(seq_len)
    weights = softmax(scores, axis=-1)
    context = (weights @ v).transpose(0, 2, 1, 3).reshape(batch, seq_len, d_model)
    return context @ w_o


class TinyGPT:
    def __init__(self, weights: dict):
        self.d_model = int(weights["config"][1])
        self.n_heads = int(weights["config"][2])
        self.max_seq_len = int(weights["config"][3])
        self.pos = positional_encoding(self.max_seq_len, self.d_model)
        for key, value in weights.items():
            if key != "config":
                setattr(self, key, value)

    @classmethod
    def load(cls, path):
        data = np.load(path)
        return cls({key: data[key] for key in data.files})

    def forward(self, token_ids):
        seq_len = token_ids.shape[1]
        x = self.embeddings[token_ids] + self.pos[:seq_len]
        x = layer_norm(
            x + attention(x, self.w_q, self.w_k, self.w_v, self.w_o, self.n_heads),
            self.ln1_gamma, self.ln1_beta,
        )
        ff = gelu(x @ self.ff_w1 + self.ff_b1) @ self.ff_w2 + self.ff_b2
        x = layer_norm(x + ff, self.ln2_gamma, self.ln2_beta)
        return x @ self.lm_head

    def generate(self, prompt, max_new_tokens=8):
        ids = tokenize(prompt)
        for _ in range(max_new_tokens):
            logits = self.forward(np.array([ids[-self.max_seq_len:]], dtype=np.int64))
            next_id = int(np.argmax(logits[0, -1]))
            ids.append(next_id)
            if next_id == END_ID:
                break
        return detokenize(ids)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", default="weights.npz")
    parser.add_argument("--prompt", default="the cat and the")
    args = parser.parse_args()

    model = TinyGPT.load(args.weights)
    print(model.generate(args.prompt))
```

Training (optional, already done for you) is a separate script:

```bash
python3 train.py --epochs 400 --out weights.npz
```

That saves the weights. Day-to-day you only run inference.

---

## Tiny GPT vs GPT-4

| | This post | GPT-4 |
|--|--|--|
| Words | 20 | ~100,000 |
| Numbers per word | 64 | thousands |
| Layers | 1 | 120+ |
| Training | seconds on CPU (once) | months on GPUs |
| Inference | load `.npz` → generate | same idea, bigger model |

Same idea. Different scale.

---

## Why Bother?

Once you see the loop, everyday LLM behavior makes sense:

- **Hallucination** — it predicts plausible words, not facts
- **Temperature** — more randomness in word picking
- **Context limits** — longer text = more computation
- **Token billing** — you pay per token, not per word

And the training-vs-inference split matches how you use ChatGPT: the expensive learning already happened; you're just running the frozen model.

---

## Next Steps

- Full code + pretrained weights: `examples/tiny-llm/`
- Try your own prompts: `python3 tiny_llm_inference.py --prompt "the dog and the"`
- Retrain if you want: `python3 train.py`
- Original paper: [Attention Is All You Need](https://arxiv.org/abs/1706.03762)

LLMs aren't magic. They're a loop that predicts the next word. Load a tiny one, watch it generate, and the black box disappears.
