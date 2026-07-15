"""Train the tiny GPT once and save weights for inference."""

from __future__ import annotations

import argparse
from pathlib import Path

from tiny_llm.data import training_sentences
from tiny_llm.model import TinyGPT, TinyGPTConfig, pad_batch
from tiny_llm.vocab import detokenize, tokenize

DEFAULT_WEIGHTS = Path(__file__).resolve().parent / "weights.npz"


def train(epochs: int = 400, seed: int = 42) -> TinyGPT:
    sequences = training_sentences()
    model = TinyGPT(TinyGPTConfig(seed=seed))
    model.learning_rate = 0.08

    for epoch in range(1, epochs + 1):
        batch = pad_batch(sequences)
        loss = model.train_step(batch)
        if epoch % 50 == 0 or epoch == 1:
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
        ids = model.generate(tokenize(prompt), max_new_tokens=6, greedy=True)
        print(f"{prompt!r} -> {detokenize(ids)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Train the tiny GPT and save weights")
    parser.add_argument("--epochs", type=int, default=400)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", type=Path, default=DEFAULT_WEIGHTS)
    args = parser.parse_args()

    model = train(epochs=args.epochs, seed=args.seed)
    model.save(args.out)
    print(f"\nsaved weights -> {args.out}")
    demo(model)
