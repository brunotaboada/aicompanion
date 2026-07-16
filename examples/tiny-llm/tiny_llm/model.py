"""Minimal tiny GPT — single-head attention, NumPy only.

Architecture:
  tokens → embeddings + positions → attention → residual → next-word scores

No multi-head, no feed-forward net, no GELU. Easy to read end to end.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np

from .vocab import END_ID, PAD_ID, VOCAB_SIZE

D_MODEL = 32
MAX_LEN = 16


def softmax(x: np.ndarray, axis: int = -1) -> np.ndarray:
    x = x - np.max(x, axis=axis, keepdims=True)
    e = np.exp(x)
    return e / np.sum(e, axis=axis, keepdims=True)


def causal_mask(n: int) -> np.ndarray:
    return np.triu(np.full((n, n), -1e9), 1)


class TinyGPT:
    """One attention layer + language-model head."""

    def __init__(self, seed: int = 42):
        rng = np.random.default_rng(seed)
        s = 0.02
        self.emb = rng.normal(0, s, (VOCAB_SIZE, D_MODEL))
        self.pos = rng.normal(0, s, (MAX_LEN, D_MODEL))
        self.Wq = rng.normal(0, s, (D_MODEL, D_MODEL))
        self.Wk = rng.normal(0, s, (D_MODEL, D_MODEL))
        self.Wv = rng.normal(0, s, (D_MODEL, D_MODEL))
        self.head = rng.normal(0, s, (D_MODEL, VOCAB_SIZE))
        self.lr = 0.1

    def forward(self, ids: np.ndarray) -> tuple[np.ndarray, dict]:
        """ids shape (n,) → logits shape (n, vocab)."""
        n = len(ids)
        x0 = self.emb[ids] + self.pos[:n]
        Q, K, V = x0 @ self.Wq, x0 @ self.Wk, x0 @ self.Wv
        scores = Q @ K.T / np.sqrt(D_MODEL) + causal_mask(n)
        A = softmax(scores, axis=-1)
        ctx = A @ V
        h = x0 + ctx
        logits = h @ self.head
        return logits, {"ids": ids, "x0": x0, "Q": Q, "K": K, "V": V, "A": A, "h": h}

    def train_step(self, ids: np.ndarray) -> float:
        """Next-token loss on one sequence (skip PAD targets)."""
        logits, c = self.forward(ids)
        targets = ids[1:]
        pred = logits[:-1]
        valid = targets != PAD_ID
        if not np.any(valid):
            return 0.0

        probs = softmax(pred, axis=-1)
        loss = -np.log(probs[np.arange(len(targets)), targets][valid] + 1e-9).mean()

        # dL/dlogits
        dlogits = probs
        dlogits[np.arange(len(targets)), targets] -= 1.0
        dlogits[~valid] = 0.0
        dlogits /= max(int(valid.sum()), 1)

        dh = np.zeros_like(c["h"])
        dh[:-1] = dlogits @ self.head.T
        dhead = c["h"][:-1].T @ dlogits

        dx0 = dh  # residual
        dctx = dh
        dA = dctx @ c["V"].T
        dV = c["A"].T @ dctx

        # softmax backward
        dscores = c["A"] * (dA - np.sum(dA * c["A"], axis=-1, keepdims=True))
        dscores /= np.sqrt(D_MODEL)

        dQ = dscores @ c["K"]
        dK = dscores.T @ c["Q"]
        dx0 = dx0 + dQ @ self.Wq.T + dK @ self.Wk.T + dV @ self.Wv.T

        dWq = c["x0"].T @ dQ
        dWk = c["x0"].T @ dK
        dWv = c["x0"].T @ dV

        demb = np.zeros_like(self.emb)
        dpos = np.zeros_like(self.pos)
        np.add.at(demb, ids, dx0)
        dpos[: len(ids)] += dx0

        lr = self.lr
        self.emb -= lr * demb
        self.pos -= lr * dpos
        self.Wq -= lr * dWq
        self.Wk -= lr * dWk
        self.Wv -= lr * dWv
        self.head -= lr * dhead
        return float(loss)

    def generate(self, prompt_ids: list[int], max_new: int = 8) -> list[int]:
        ids = list(prompt_ids)
        for _ in range(max_new):
            logits, _ = self.forward(np.array(ids[-MAX_LEN:], dtype=np.int64))
            nxt = int(np.argmax(logits[-1]))
            ids.append(nxt)
            if nxt == END_ID:
                break
        return ids

    def save(self, path: str | Path) -> None:
        np.savez_compressed(
            path,
            emb=self.emb,
            pos=self.pos,
            Wq=self.Wq,
            Wk=self.Wk,
            Wv=self.Wv,
            head=self.head,
        )

    @classmethod
    def load(cls, path: str | Path) -> TinyGPT:
        data = np.load(path)
        model = cls()
        model.emb = data["emb"]
        model.pos = data["pos"]
        model.Wq = data["Wq"]
        model.Wk = data["Wk"]
        model.Wv = data["Wv"]
        model.head = data["head"]
        return model
