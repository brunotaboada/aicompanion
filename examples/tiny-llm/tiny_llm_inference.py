"""Load pretrained weights and generate text. No training."""

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
END = WORD_TO_ID["END"]


def softmax(x):
    e = np.exp(x - x.max(axis=-1, keepdims=True))
    return e / e.sum(axis=-1, keepdims=True)


def attention(x, Wq, Wk, Wv):
    """Single-head attention. x shape: (n_words, d)."""
    Q, K, V = x @ Wq, x @ Wk, x @ Wv
    n = len(x)
    mask = np.triu(np.full((n, n), -1e9), 1)  # can't look ahead
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
        x = w["emb"][ids] + w["pos"][: len(ids)]
        x = x + attention(x, w["Wq"], w["Wk"], w["Wv"])
        return x @ w["head"]  # score per vocab word

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
    parser.add_argument("--weights", default=str(Path(__file__).with_name("weights.npz")))
    parser.add_argument("--prompt", default="the cat and the")
    args = parser.parse_args()

    model = TinyGPT.load(args.weights)
    for prompt in [
        args.prompt,
        "the big cat sat on the",
        "the red big cat sat on the",
        "the small dog ran to the small",
    ]:
        print(f"{prompt!r} -> {model.generate(prompt)}")
