"""Tiny GPT inference only — load pretrained weights and generate text.

No training / no backpropagation. Run:

    python3 tiny_llm_inference.py
    python3 tiny_llm_inference.py --prompt "the cat and the"
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

# -----------------------------
# Vocabulary
# -----------------------------

VOCAB = [
    "the", "cat", "dog", "sat", "ran", "on", "mat", "house", "a",
    "big", "small", "quickly", "slowly", "and", "is", "red", "blue", "to",
    "PAD", "END",
]
WORD_TO_ID = {word: i for i, word in enumerate(VOCAB)}
ID_TO_WORD = {i: word for i, word in enumerate(VOCAB)}
END_ID = WORD_TO_ID["END"]
VOCAB_SIZE = len(VOCAB)


def tokenize(text: str) -> list[int]:
    return [WORD_TO_ID[word] for word in text.lower().split()]


def detokenize(ids: list[int]) -> str:
    return " ".join(ID_TO_WORD[i] for i in ids)


# -----------------------------
# Forward-pass building blocks
# -----------------------------

def softmax(x: np.ndarray, axis: int = -1) -> np.ndarray:
    x = x - np.max(x, axis=axis, keepdims=True)
    e = np.exp(x)
    return e / np.sum(e, axis=axis, keepdims=True)


def gelu(x: np.ndarray) -> np.ndarray:
    return 0.5 * x * (1.0 + np.tanh(np.sqrt(2.0 / np.pi) * (x + 0.044715 * x**3)))


def layer_norm(x: np.ndarray, gamma: np.ndarray, beta: np.ndarray, eps: float = 1e-5) -> np.ndarray:
    mean = x.mean(axis=-1, keepdims=True)
    var = ((x - mean) ** 2).mean(axis=-1, keepdims=True)
    return gamma * (x - mean) / np.sqrt(var + eps) + beta


def positional_encoding(seq_len: int, d_model: int) -> np.ndarray:
    position = np.arange(seq_len)[:, None]
    div = np.exp(np.arange(0, d_model, 2) * (-np.log(10000.0) / d_model))
    pe = np.zeros((seq_len, d_model))
    pe[:, 0::2] = np.sin(position * div)
    pe[:, 1::2] = np.cos(position * div)
    return pe


def causal_mask(seq_len: int) -> np.ndarray:
    return np.triu(np.ones((seq_len, seq_len)), k=1) * -1e9


def attention(x: np.ndarray, w_q, w_k, w_v, w_o, n_heads: int) -> np.ndarray:
    batch, seq_len, d_model = x.shape
    d_head = d_model // n_heads

    q = (x @ w_q).reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)
    k = (x @ w_k).reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)
    v = (x @ w_v).reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)

    scores = q @ k.transpose(0, 1, 3, 2) / np.sqrt(d_head)
    scores = scores + causal_mask(seq_len)
    weights = softmax(scores, axis=-1)
    context = (weights @ v).transpose(0, 2, 1, 3).reshape(batch, seq_len, d_model)
    return context @ w_o


# -----------------------------
# Model (inference only)
# -----------------------------

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
    def load(cls, path: str | Path) -> TinyGPT:
        data = np.load(path)
        return cls({key: data[key] for key in data.files})

    def forward(self, token_ids: np.ndarray) -> np.ndarray:
        """token_ids: (batch, seq) → logits: (batch, seq, vocab)"""
        seq_len = token_ids.shape[1]
        x = self.embeddings[token_ids] + self.pos[:seq_len]

        x = layer_norm(x + attention(x, self.w_q, self.w_k, self.w_v, self.w_o, self.n_heads),
                       self.ln1_gamma, self.ln1_beta)
        ff = gelu(x @ self.ff_w1 + self.ff_b1) @ self.ff_w2 + self.ff_b2
        x = layer_norm(x + ff, self.ln2_gamma, self.ln2_beta)
        return x @ self.lm_head

    def generate(self, prompt: str, max_new_tokens: int = 8) -> str:
        ids = tokenize(prompt)
        for _ in range(max_new_tokens):
            logits = self.forward(np.array([ids[-self.max_seq_len:]], dtype=np.int64))
            next_id = int(np.argmax(logits[0, -1]))
            ids.append(next_id)
            if next_id == END_ID:
                break
        return detokenize(ids)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Tiny GPT inference")
    parser.add_argument("--weights", default=str(Path(__file__).with_name("weights.npz")))
    parser.add_argument("--prompt", default="the cat and the")
    args = parser.parse_args()

    model = TinyGPT.load(args.weights)
    prompts = [
        args.prompt,
        "the big cat sat on the",
        "the red big cat sat on the",
        "the small dog ran to the small",
    ]
    # If user passed a custom prompt, still show the demos after it.
    seen = set()
    for prompt in prompts:
        if prompt in seen:
            continue
        seen.add(prompt)
        print(f"{prompt!r} -> {model.generate(prompt)}")
