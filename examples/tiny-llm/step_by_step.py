"""Step-by-step building blocks (matches the blog post sections)."""

from __future__ import annotations

import numpy as np

from tiny_llm.layers import causal_mask, positional_encoding, softmax
from tiny_llm.vocab import VOCAB, WORD_TO_ID, tokenize


def demo_softmax() -> None:
    scores = np.array([2.3, 5.1, -1.2, 0.4])
    probs = softmax(scores)
    print("softmax scores ->", dict(zip(["mat", "floor", "banana", "rug"], probs.round(3))))


def demo_embeddings() -> None:
    rng = np.random.default_rng(0)
    d_model = 8
    embeddings = rng.normal(0, 0.2, (len(VOCAB), d_model))
    cat = embeddings[WORD_TO_ID["cat"]]
    dog = embeddings[WORD_TO_ID["dog"]]
    table = embeddings[WORD_TO_ID["mat"]]  # closest furniture word in vocab
    print("cat·dog =", float(cat @ dog))
    print("cat·mat =", float(cat @ table))


def demo_attention_weights() -> None:
    ids = tokenize("the big cat sat")
    rng = np.random.default_rng(1)
    d_model = 8
    embeddings = rng.normal(0, 0.2, (len(VOCAB), d_model))
    x = embeddings[ids] + positional_encoding(len(ids), d_model)

    w_q = rng.normal(0, 0.1, (d_model, d_model))
    w_k = rng.normal(0, 0.1, (d_model, d_model))
    q = x @ w_q
    k = x @ w_k
    scores = q @ k.T / np.sqrt(d_model) + causal_mask(len(ids))
    weights = softmax(scores, axis=-1)

    words = ["the", "big", "cat", "sat"]
    print("attention weights (rows attend to columns):")
    for i, word in enumerate(words):
        row = ", ".join(f"{w}:{weights[i, j]:.2f}" for j, w in enumerate(words))
        print(f"  {word:>3} -> {row}")


if __name__ == "__main__":
    demo_softmax()
    print()
    demo_embeddings()
    print()
    demo_attention_weights()
