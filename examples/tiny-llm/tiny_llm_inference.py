"""
Tiny GPT — inference only.

For each new word we:
  1. look up embeddings (meaning + position)
  2. let words share context with attention
  3. score every vocab word and pick the best one

weights.npz was trained ahead of time. This file only reads it.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

# ---- vocabulary -----------------------------------------------------------
# Tiny closed world. If you type an unknown word, it fails on purpose.
# Real models would split unknown words into subword pieces.
VOCAB = [
    "the", "cat", "dog", "sat", "ran", "on", "mat", "house", "a",
    "big", "small", "quickly", "slowly", "and", "is", "red", "blue", "to",
    "PAD", "END",
]
WORD_TO_ID = {w: i for i, w in enumerate(VOCAB)}
ID_TO_WORD = {i: w for i, w in enumerate(VOCAB)}
END = WORD_TO_ID["END"]  # stop signal


def softmax(x):
    """Turn raw scores into probabilities that add to 1."""
    # Subtract max first so exp() doesn't blow up on large numbers.
    x = x - x.max(axis=-1, keepdims=True)
    e = np.exp(x)
    return e / e.sum(axis=-1, keepdims=True)


def attention(x, Wq, Wk, Wv):
    """
    Single-head attention.

    x shape: (n_words, d)
    Returns one new vector per word, each mixed from earlier words.
    """
    # Same input, three learned views of it.
    Q = x @ Wq  # "what am I looking for?"
    K = x @ Wk  # "how should others find me?"
    V = x @ Wv  # "what do I contribute if selected?"

    n, d = x.shape

    # Compare every query to every key → relevance scores.
    # / sqrt(d) keeps scores from getting huge as d grows.
    scores = (Q @ K.T) / np.sqrt(d)

    # Block the future: word i cannot look at words after i.
    # Big negative numbers become ~0 after softmax.
    future = np.triu(np.full((n, n), -1e9), k=1)
    weights = softmax(scores + future)  # each row = "how much I attend to each word"

    # Mix the values using those weights.
    # Example: weights [0.1, 0.7, 0.2] → mostly the 2nd word's value.
    return weights @ V


class TinyGPT:
    """
    Minimal decoder:
      embed → attend → predict next word

    weights.npz contains:
      emb  — meaning vector for each vocab word
      pos  — position vector for each index in the sentence
      Wq, Wk, Wv — attention projection matrices
      head — final "score every vocab word" matrix
    """

    def __init__(self, weights):
        self.w = weights

    @classmethod
    def load(cls, path):
        """Load a trained checkpoint. We never update weights here."""
        return cls(dict(np.load(path)))

    def forward(self, ids):
        """
        One forward pass over the current sentence so far.

        ids:    [0, 1, 3]           # "the cat sat"
        return: scores for each vocab word at each position
        """
        w = self.w
        n = len(ids)

        # 1) Meaning + position for every word.
        #    emb[ids] picks rows from the embedding table.
        x = w["emb"][ids] + w["pos"][:n]

        # 2) Share context. Residual "x +" keeps the original signal
        #    and lets attention add useful extras on top.
        x = x + attention(x, w["Wq"], w["Wk"], w["Wv"])

        # 3) Turn each position into scores over the full vocabulary.
        #    At generation time we only need the last row (next word).
        return x @ w["head"]

    def generate(self, prompt, max_new=8):
        """
        Keep guessing the next word until END (or max_new guesses).

        Example:
          prompt "the cat and the"
          → "the cat and the dog END"
        """
        ids = [WORD_TO_ID[w] for w in prompt.lower().split()]
        for _ in range(max_new):
            scores = self.forward(np.array(ids))
            next_id = int(scores[-1].argmax())  # greedy: pick the top score
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
