"""
Tiny GPT — inference only.

Computational graph (per decoding step):
  token ids
    → token embedding + positional embedding     (lookup)
    → causal self-attention + residual           (context mixing)
    → LM head                                    (vocab logits)
    → argmax                                     (greedy next token)

Parameters live in weights.npz (trained offline). This file never updates them.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

# ---------------------------------------------------------------------------
# Vocabulary
# ---------------------------------------------------------------------------
# Closed world of 20 tokens. Unknown words raise KeyError on purpose —
# a production tokenizer would fall back to subword pieces / <unk>.
VOCAB = [
    "the", "cat", "dog", "sat", "ran", "on", "mat", "house", "a",
    "big", "small", "quickly", "slowly", "and", "is", "red", "blue", "to",
    "PAD", "END",
]
WORD_TO_ID = {w: i for i, w in enumerate(VOCAB)}
ID_TO_WORD = {i: w for i, w in enumerate(VOCAB)}
END = WORD_TO_ID["END"]  # stop token for the decoding loop


def softmax(x: np.ndarray, axis: int = -1) -> np.ndarray:
    """Numerically stable softmax → categorical distribution along `axis`."""
    x = x - np.max(x, axis=axis, keepdims=True)  # prevents exp overflow
    exp = np.exp(x)
    return exp / np.sum(exp, axis=axis, keepdims=True)


def attention(x: np.ndarray, Wq: np.ndarray, Wk: np.ndarray, Wv: np.ndarray) -> np.ndarray:
    """
    Single-head causal self-attention.

    Args:
        x:  (n, d) hidden states for the current prefix
        Wq, Wk, Wv: (d, d) projection matrices learned during training

    Returns:
        (n, d) context vectors — each row is a weighted mix of values
        from positions ≤ that row (causality enforced by the mask).
    """
    # Linear projections: same x, three different learned views.
    Q = x @ Wq  # (n, d) — "what am I searching for?"
    K = x @ Wk  # (n, d) — "how do I present myself to searchers?"
    V = x @ Wv  # (n, d) — "what content do I contribute if selected?"

    d = x.shape[1]
    n = x.shape[0]

    # Compatibility scores. Divide by sqrt(d) so dot products don't grow
    # with dimension and push softmax into saturated (near one-hot) regimes.
    scores = (Q @ K.T) / np.sqrt(d)  # (n, n)

    # Causal mask: position i may attend to j only if j <= i.
    # Setting future scores to a large negative makes softmax ≈ 0 there.
    causal = np.triu(np.full((n, n), -1e9), k=1)
    weights = softmax(scores + causal, axis=-1)  # (n, n), rows sum to 1

    # Mix values with the attention weights.
    return weights @ V  # (n, d)


class TinyGPT:
    """
    Minimal decoder:
      Embed(tokens, positions) → Attention → residual → LM head

    Weight file keys:
      emb  (V, d)   token embedding table
      pos  (L, d)   positional embedding table
      Wq, Wk, Wv (d, d)  attention projections
      head (d, V)   vocabulary projection (ties the model to next-token prediction)
    """

    def __init__(self, weights: dict):
        self.w = weights

    @classmethod
    def load(cls, path: str | Path) -> TinyGPT:
        """Deserialize a trained checkpoint. Inference never writes back."""
        return cls(dict(np.load(path)))

    def forward(self, ids: np.ndarray) -> np.ndarray:
        """
        Full forward pass for a token prefix.

        Args:
            ids: (n,) int token ids
        Returns:
            logits: (n, V) — row t is P(next token | ids[:t+1]) before softmax
        """
        w = self.w
        n = len(ids)

        # 1. Representation: token identity + absolute position.
        #    Without `pos`, attention cannot distinguish order.
        x = w["emb"][ids] + w["pos"][:n]  # (n, d)

        # 2. Context mixing. Residual connection (x + attn) preserves the
        #    original embedding while allowing attention to add refinements —
        #    the same pattern used inside Transformer blocks at scale.
        x = x + attention(x, w["Wq"], w["Wk"], w["Wv"])

        # 3. Language-model head: hidden state → score for every vocab entry.
        #    We read logits[-1] at generation time (predict given the full prefix).
        return x @ w["head"]  # (n, V)

    def generate(self, prompt: str, max_new: int = 8) -> str:
        """
        Greedy autoregressive decoding.

        At each step we condition on the growing prefix, take argmax over the
        final position's logits, and stop on END (or max_new tokens).
        """
        ids = [WORD_TO_ID[token] for token in prompt.lower().split()]
        for _ in range(max_new):
            logits = self.forward(np.asarray(ids, dtype=np.int64))
            next_id = int(logits[-1].argmax())  # greedy ≡ temperature → 0
            ids.append(next_id)
            if next_id == END:
                break
        return " ".join(ID_TO_WORD[i] for i in ids)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run tiny GPT inference")
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
