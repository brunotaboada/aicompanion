# Build a Tiny LLM in Python (20 Words, NumPy Only)

ChatGPT feels like magic until it doesn't. It hallucinates, repeats itself, or ignores something you said three sentences ago. Most of us treat LLMs as black boxes.

They're not. Under the hood, every LLM is **next-word prediction in a loop** — tokenize, embed, attend, predict, repeat.

This post walks through a **tiny GPT with only 20 words**, in pure NumPy. We'll start with one tiny sentence — `"the cat sat"` — and gradually expand to a small vocabulary so each example builds on the last.

> **Runnable version in this post:** copy the full code below into `tiny_llm_from_scratch.py`, then run:  
> `python3 tiny_llm_from_scratch.py --epochs 400`

---

## What an LLM Actually Does

Given some text, the model guesses the next word. Then it adds that word and guesses again.

```python
words = ["the", "cat"]          # input so far
next_word = model.predict(words) # → "sat"
words.append(next_word)          # ["the", "cat", "sat"]
next_word = model.predict(words) # → "on" (or END to stop)
```

That's the whole loop behind ChatGPT. Training teaches the model good guesses. Inference just runs the loop.

---

## The Tiny Model We'll Build

The complete script below implements a small GPT-style decoder in one file:

- a 20-word vocabulary
- tokenization and detokenization
- token embeddings and sinusoidal positional encoding
- causal self-attention so the model cannot peek at future words
- one Transformer block with attention, residual connections, layer normalization, and a feed-forward network
- a language-model head that predicts the next token
- a small NumPy training loop and greedy text generation demo

This is not meant to compete with real LLMs. It is intentionally tiny so you can read the whole thing and see how the pieces fit together.

---

## Full Working Code

Save this as `tiny_llm_from_scratch.py`:

```python
"""Self-contained tiny GPT-style language model in NumPy.

Run:
    python3 tiny_llm_from_scratch.py --epochs 400
"""

from __future__ import annotations

import argparse
import numpy as np

# -----------------------------
# 1. Vocabulary and data
# -----------------------------

VOCAB = [
    "the", "cat", "dog", "sat", "ran", "on", "mat", "house", "a",
    "big", "small", "quickly", "slowly", "and", "is", "red", "blue", "to",
    "PAD", "END",
]
WORD_TO_ID = {word: i for i, word in enumerate(VOCAB)}
ID_TO_WORD = {i: word for i, word in enumerate(VOCAB)}
PAD_ID = WORD_TO_ID["PAD"]
END_ID = WORD_TO_ID["END"]
VOCAB_SIZE = len(VOCAB)


def tokenize(text: str) -> list[int]:
    ids: list[int] = []
    for word in text.lower().strip().split():
        if word not in WORD_TO_ID:
            raise ValueError(f"Unknown word {word!r}. Vocabulary has only {VOCAB_SIZE} words.")
        ids.append(WORD_TO_ID[word])
    return ids


def detokenize(ids: list[int]) -> str:
    return " ".join(ID_TO_WORD[int(i)] for i in ids)


def training_sentences() -> list[list[int]]:
    sentences: list[str] = []

    for size in ("big", "small"):
        for animal in ("cat", "dog"):
            sentences.append(f"the {size} {animal} sat on the {size} mat")
            sentences.append(f"the {size} {animal} ran to the {size} house")
            for color in ("red", "blue"):
                sentences.append(f"the {color} {size} {animal} sat on the {size} mat")
                sentences.append(f"the {color} {size} {animal} ran to the {size} house")

    sentences.extend([
        "the red cat sat on the mat",
        "the blue dog ran to the house",
        "the red cat sat on the house",
        "the blue dog ran to the mat",
    ])

    sentences.extend(["the cat and the dog"] * 8)
    sentences.extend(["the dog and the cat"] * 8)
    sentences.extend(["the cat and the cat", "the dog and the dog"])

    return [tokenize(sentence) + [END_ID] for sentence in sentences]


def pad_batch(sequences: list[list[int]], pad_id: int = PAD_ID) -> np.ndarray:
    max_len = max(len(sequence) for sequence in sequences)
    batch = np.full((len(sequences), max_len), pad_id, dtype=np.int64)
    for row, sequence in enumerate(sequences):
        batch[row, : len(sequence)] = sequence
    return batch


# -----------------------------
# 2. Tiny neural-network pieces
# -----------------------------


def softmax(x: np.ndarray, axis: int = -1) -> np.ndarray:
    x = x - np.max(x, axis=axis, keepdims=True)
    exp = np.exp(x)
    return exp / np.sum(exp, axis=axis, keepdims=True)


def gelu(x: np.ndarray) -> np.ndarray:
    return 0.5 * x * (1.0 + np.tanh(np.sqrt(2.0 / np.pi) * (x + 0.044715 * x**3)))


def gelu_derivative(x: np.ndarray) -> np.ndarray:
    inner = np.sqrt(2.0 / np.pi) * (x + 0.044715 * x**3)
    tanh_inner = np.tanh(inner)
    sech2 = 1.0 - tanh_inner**2
    d_inner = np.sqrt(2.0 / np.pi) * (1.0 + 3.0 * 0.044715 * x**2)
    return 0.5 * (1.0 + tanh_inner) + 0.5 * x * sech2 * d_inner


def causal_mask(seq_len: int) -> np.ndarray:
    mask = np.triu(np.ones((seq_len, seq_len), dtype=np.float64), k=1)
    return mask * -1e9


def positional_encoding(seq_len: int, d_model: int) -> np.ndarray:
    position = np.arange(seq_len)[:, None]
    div = np.exp(np.arange(0, d_model, 2) * (-np.log(10000.0) / d_model))
    pe = np.zeros((seq_len, d_model), dtype=np.float64)
    pe[:, 0::2] = np.sin(position * div)
    pe[:, 1::2] = np.cos(position * div)
    return pe


def layer_norm(x: np.ndarray, gamma: np.ndarray, beta: np.ndarray, eps: float = 1e-5):
    mean = x.mean(axis=-1, keepdims=True)
    var = ((x - mean) ** 2).mean(axis=-1, keepdims=True)
    std = np.sqrt(var + eps)
    normalized = (x - mean) / std
    out = gamma * normalized + beta
    return out, (x, normalized, mean, std, gamma)


def layer_norm_backward(grad_out: np.ndarray, cache) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    x, normalized, mean, std, gamma = cache
    d_norm = grad_out * gamma
    n = x.shape[-1]
    d_var = np.sum(d_norm * (x - mean) * -0.5 * std**-3, axis=-1, keepdims=True)
    d_mean = np.sum(d_norm * -1.0 / std, axis=-1, keepdims=True) + d_var * np.mean(-2.0 * (x - mean), axis=-1, keepdims=True)
    grad_x = d_norm / std + d_var * 2.0 * (x - mean) / n + d_mean / n
    reduce_axes = tuple(range(x.ndim - 1))
    grad_gamma = np.sum(grad_out * normalized, axis=reduce_axes)
    grad_beta = np.sum(grad_out, axis=reduce_axes)
    return grad_x, grad_gamma, grad_beta


def linear(x: np.ndarray, w: np.ndarray, b: np.ndarray):
    return x @ w + b, (x, w)


def linear_backward(grad_out: np.ndarray, cache) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    x, w = cache
    grad_w = x.reshape(-1, x.shape[-1]).T @ grad_out.reshape(-1, grad_out.shape[-1])
    grad_b = grad_out.reshape(-1, grad_out.shape[-1]).sum(axis=0)
    grad_x = grad_out @ w.T
    return grad_x, grad_w, grad_b


def multi_head_attention(x: np.ndarray, w_q: np.ndarray, w_k: np.ndarray, w_v: np.ndarray, w_o: np.ndarray, n_heads: int):
    batch, seq_len, d_model = x.shape
    d_head = d_model // n_heads

    q, _ = linear(x, w_q, np.zeros(d_model))
    k, _ = linear(x, w_k, np.zeros(d_model))
    v, _ = linear(x, w_v, np.zeros(d_model))

    def split_heads(t: np.ndarray) -> np.ndarray:
        return t.reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)

    qh, kh, vh = split_heads(q), split_heads(k), split_heads(v)
    scores = qh @ kh.transpose(0, 1, 3, 2) / np.sqrt(d_head)
    scores = scores + causal_mask(seq_len)
    weights = softmax(scores, axis=-1)
    context = weights @ vh
    context = context.transpose(0, 2, 1, 3).reshape(batch, seq_len, d_model)
    out, lin_cache = linear(context, w_o, np.zeros(d_model))

    cache = {
        "x": x, "q": q, "k": k, "v": v, "weights": weights, "vh": vh,
        "n_heads": n_heads, "d_head": d_head, "w_q": w_q, "w_k": w_k,
        "w_v": w_v, "lin_cache": lin_cache,
    }
    return out, cache


def multi_head_attention_backward(grad_out: np.ndarray, cache) -> dict[str, np.ndarray]:
    x = cache["x"]
    batch, seq_len, d_model = x.shape
    n_heads, d_head = cache["n_heads"], cache["d_head"]

    grad_context, grad_w_o, _ = linear_backward(grad_out, cache["lin_cache"])
    grad_context = grad_context.reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)
    weights, vh = cache["weights"], cache["vh"]

    grad_vh = weights.transpose(0, 1, 3, 2) @ grad_context
    grad_weights = grad_context @ vh.transpose(0, 1, 3, 2)
    grad_scores = weights * (grad_weights - np.sum(grad_weights * weights, axis=-1, keepdims=True))
    grad_scores = grad_scores / np.sqrt(d_head)

    qh = cache["q"].reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)
    kh = cache["k"].reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)
    grad_qh = grad_scores @ kh
    grad_kh = grad_scores.transpose(0, 1, 3, 2) @ qh

    def merge_heads(t: np.ndarray) -> np.ndarray:
        return t.transpose(0, 2, 1, 3).reshape(batch, seq_len, d_model)

    grad_q, grad_w_q, _ = linear_backward(merge_heads(grad_qh), (x, cache["w_q"]))
    grad_k, grad_w_k, _ = linear_backward(merge_heads(grad_kh), (x, cache["w_k"]))
    grad_v, grad_w_v, _ = linear_backward(merge_heads(grad_vh), (x, cache["w_v"]))
    return {
        "grad_x": grad_q + grad_k + grad_v,
        "grad_w_q": grad_w_q,
        "grad_w_k": grad_w_k,
        "grad_w_v": grad_w_v,
        "grad_w_o": grad_w_o,
    }


def feed_forward(x: np.ndarray, w1: np.ndarray, b1: np.ndarray, w2: np.ndarray, b2: np.ndarray):
    hidden, cache1 = linear(x, w1, b1)
    activated = gelu(hidden)
    out, cache2 = linear(activated, w2, b2)
    return out, {"hidden": hidden, "cache1": cache1, "cache2": cache2}


def feed_forward_backward(grad_out: np.ndarray, cache) -> dict[str, np.ndarray]:
    grad_act, grad_w2, grad_b2 = linear_backward(grad_out, cache["cache2"])
    grad_hidden = grad_act * gelu_derivative(cache["hidden"])
    grad_x, grad_w1, grad_b1 = linear_backward(grad_hidden, cache["cache1"])
    return {"grad_x": grad_x, "grad_w1": grad_w1, "grad_b1": grad_b1, "grad_w2": grad_w2, "grad_b2": grad_b2}


# -----------------------------
# 3. One-block tiny GPT
# -----------------------------

class TinyGPT:
    def __init__(self, d_model: int = 64, n_heads: int = 4, max_seq_len: int = 16, seed: int = 42):
        self.d_model = d_model
        self.n_heads = n_heads
        self.max_seq_len = max_seq_len
        self.learning_rate = 0.08
        rng = np.random.default_rng(seed)
        scale = 0.02

        self.embeddings = rng.normal(0, scale, (VOCAB_SIZE, d_model))
        self.pos_encoding = positional_encoding(max_seq_len, d_model)
        self.ln1_gamma, self.ln1_beta = np.ones(d_model), np.zeros(d_model)
        self.ln2_gamma, self.ln2_beta = np.ones(d_model), np.zeros(d_model)
        self.w_q = rng.normal(0, scale, (d_model, d_model))
        self.w_k = rng.normal(0, scale, (d_model, d_model))
        self.w_v = rng.normal(0, scale, (d_model, d_model))
        self.w_o = rng.normal(0, scale, (d_model, d_model))
        self.ff_w1 = rng.normal(0, scale, (d_model, d_model * 2))
        self.ff_b1 = np.zeros(d_model * 2)
        self.ff_w2 = rng.normal(0, scale, (d_model * 2, d_model))
        self.ff_b2 = np.zeros(d_model)
        self.lm_head = rng.normal(0, scale, (d_model, VOCAB_SIZE))

    def forward(self, token_ids: np.ndarray):
        _, seq_len = token_ids.shape
        x = self.embeddings[token_ids] + self.pos_encoding[:seq_len]

        attn_out, attn_cache = multi_head_attention(x, self.w_q, self.w_k, self.w_v, self.w_o, self.n_heads)
        x = x + attn_out
        ln1_out, ln1_cache = layer_norm(x, self.ln1_gamma, self.ln1_beta)

        ff_out, ff_cache = feed_forward(ln1_out, self.ff_w1, self.ff_b1, self.ff_w2, self.ff_b2)
        x = ln1_out + ff_out
        ln2_out, ln2_cache = layer_norm(x, self.ln2_gamma, self.ln2_beta)
        logits = ln2_out @ self.lm_head

        return logits, {
            "token_ids": token_ids, "attn_cache": attn_cache, "ln1_cache": ln1_cache,
            "ff_cache": ff_cache, "ln2_cache": ln2_cache,
        }

    def backward(self, grad_logits: np.ndarray, cache) -> None:
        token_ids = cache["token_ids"]
        normalized = cache["ln2_cache"][1]

        grad_lm_head = normalized.reshape(-1, self.d_model).T @ grad_logits.reshape(-1, VOCAB_SIZE)
        grad_ln2 = grad_logits @ self.lm_head.T
        grad_x, grad_ln2_gamma, grad_ln2_beta = layer_norm_backward(grad_ln2, cache["ln2_cache"])

        ff_grads = feed_forward_backward(grad_x, cache["ff_cache"])
        grad_ln1 = ff_grads["grad_x"] + grad_x
        grad_x, grad_ln1_gamma, grad_ln1_beta = layer_norm_backward(grad_ln1, cache["ln1_cache"])

        attn_grads = multi_head_attention_backward(grad_x, cache["attn_cache"])
        grad_embed = attn_grads["grad_x"] + grad_x

        grad_embeddings = np.zeros_like(self.embeddings)
        np.add.at(grad_embeddings, token_ids, grad_embed)

        lr = self.learning_rate
        self.lm_head -= lr * grad_lm_head
        self.ln2_gamma -= lr * grad_ln2_gamma
        self.ln2_beta -= lr * grad_ln2_beta
        self.ln1_gamma -= lr * grad_ln1_gamma
        self.ln1_beta -= lr * grad_ln1_beta
        self.w_q -= lr * attn_grads["grad_w_q"]
        self.w_k -= lr * attn_grads["grad_w_k"]
        self.w_v -= lr * attn_grads["grad_w_v"]
        self.w_o -= lr * attn_grads["grad_w_o"]
        self.ff_w1 -= lr * ff_grads["grad_w1"]
        self.ff_b1 -= lr * ff_grads["grad_b1"]
        self.ff_w2 -= lr * ff_grads["grad_w2"]
        self.ff_b2 -= lr * ff_grads["grad_b2"]
        self.embeddings -= lr * grad_embeddings

    def train_step(self, token_ids: np.ndarray) -> float:
        logits, cache = self.forward(token_ids)
        targets = token_ids[:, 1:]
        pred_logits = logits[:, :-1, :]
        valid = targets != PAD_ID

        probs = softmax(pred_logits, axis=-1)
        chosen = probs[np.arange(targets.shape[0])[:, None], np.arange(targets.shape[1]), targets]
        loss = -np.log(chosen[valid] + 1e-9).mean()

        grad_logits = probs.copy()
        batch_idx = np.arange(targets.shape[0])[:, None]
        pos_idx = np.arange(targets.shape[1])
        grad_logits[batch_idx, pos_idx, targets] -= 1.0
        grad_logits *= valid[..., None]
        grad_logits /= max(int(valid.sum()), 1)

        full_grad = np.zeros_like(logits)
        full_grad[:, :-1, :] = grad_logits
        self.backward(full_grad, cache)
        return float(loss)

    def generate(self, prompt_ids: list[int], max_new_tokens: int = 8, greedy: bool = True, temperature: float = 0.8) -> list[int]:
        rng = np.random.default_rng(123)
        ids = list(prompt_ids)
        for _ in range(max_new_tokens):
            window = np.array([ids[-self.max_seq_len:]], dtype=np.int64)
            logits, _ = self.forward(window)
            next_logits = logits[0, -1] / max(temperature, 1e-6)
            if greedy:
                next_id = int(np.argmax(next_logits))
            else:
                probs = softmax(next_logits[None, :], axis=-1)[0]
                next_id = int(rng.choice(len(probs), p=probs))
            ids.append(next_id)
            if next_id == END_ID:
                break
        return ids


def train(epochs: int = 400, seed: int = 42) -> TinyGPT:
    model = TinyGPT(seed=seed)
    batch = pad_batch(training_sentences())
    for epoch in range(1, epochs + 1):
        loss = model.train_step(batch)
        if epoch == 1 or epoch % 50 == 0:
            print(f"epoch {epoch:4d}  loss={loss:.4f}")
    return model


def demo(model: TinyGPT) -> None:
    prompts = [
        "the big cat sat on the",
        "the red big cat sat on the",
        "the cat and the",
        "the small dog ran to the small",
    ]
    print("\n--- generation (greedy) ---")
    for prompt in prompts:
        ids = model.generate(tokenize(prompt), max_new_tokens=8, greedy=True)
        print(f"{prompt!r} -> {detokenize(ids)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Train and demo a tiny GPT in NumPy")
    parser.add_argument("--epochs", type=int, default=400)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    demo(train(epochs=args.epochs, seed=args.seed))

```

---

## Tested Output

I tested the script with:

```bash
python3 tiny_llm_from_scratch.py --epochs 400
```

The exact loss can vary slightly by Python/NumPy version, but with the default seed the generated text should look like this:

```text
epoch    1  loss=2.9633
...
epoch  400  loss=0.6296

--- generation (greedy) ---
'the big cat sat on the' -> the big cat sat on the big mat END
'the red big cat sat on the' -> the red big cat sat on the big mat END
'the cat and the' -> the cat and the dog END
'the small dog ran to the small' -> the small dog ran to the small house END
```

---

## Tiny GPT vs Frontier LLMs

| | This post | Frontier LLMs |
|--|--|--|
| Vocabulary | 20 words | very large tokenizer vocabularies |
| Numbers per token | 64 | hundreds or thousands |
| Transformer blocks | 1 | many stacked blocks |
| Training | seconds on CPU | large-scale GPU training |

Same idea. Different scale.

---

## Why Bother?

Once you see the loop, everyday LLM behavior makes sense:

- **Hallucination** — it predicts plausible words, not facts
- **Temperature** — more randomness in word picking
- **Context limits** — longer text = more computation
- **Token billing** — you pay per token, not per word

---

## Next Steps

- Try changing the training sentences.
- Add more words to `VOCAB`.
- Increase `epochs` and compare generation quality.
- Change `greedy=True` to `greedy=False` in `demo()` and experiment with `temperature`.
- Read the original paper: [Attention Is All You Need](https://arxiv.org/abs/1706.03762).

LLMs aren't magic. They're a loop that predicts the next word. Build one small enough to see inside, and the black box disappears.
