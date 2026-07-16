"""Train the tiny GPT once and save weights for inference."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

from tiny_llm.data import training_sentences
from tiny_llm.model import TinyGPT
from tiny_llm.vocab import PAD_ID, detokenize, tokenize

DEFAULT_WEIGHTS = Path(__file__).resolve().parent / "weights.npz"


def pad(seq: list[int], length: int) -> np.ndarray:
    out = np.full(length, PAD_ID, dtype=np.int64)
    out[: len(seq)] = seq
    return out


def train(epochs: int = 800, seed: int = 42) -> TinyGPT:
    sequences = training_sentences()
    max_len = max(len(s) for s in sequences)
    model = TinyGPT(seed=seed)

    for epoch in range(1, epochs + 1):
        total = 0.0
        order = np.random.default_rng(epoch).permutation(len(sequences))
        for i in order:
            total += model.train_step(pad(sequences[i], max_len))
        if epoch == 1 or epoch % 100 == 0:
            print(f"epoch {epoch:4d}  loss={total / len(sequences):.4f}")
    return model


def demo(model: TinyGPT) -> None:
    prompts = [
        "the big cat sat on the",
        "the red big cat sat on the",
        "the cat and the",
        "the small dog ran to the small",
    ]
    print("\n--- generation ---")
    for prompt in prompts:
        ids = model.generate(tokenize(prompt), max_new=6)
        print(f"{prompt!r} -> {detokenize(ids)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--epochs", type=int, default=800)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", type=Path, default=DEFAULT_WEIGHTS)
    args = parser.parse_args()

    model = train(epochs=args.epochs, seed=args.seed)
    model.save(args.out)
    print(f"\nsaved weights -> {args.out}")
    demo(model)
