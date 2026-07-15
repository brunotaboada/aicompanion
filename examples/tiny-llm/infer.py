"""Load pretrained weights and run inference (no training)."""

from __future__ import annotations

import argparse
from pathlib import Path

from tiny_llm.model import TinyGPT
from tiny_llm.vocab import detokenize, tokenize

DEFAULT_WEIGHTS = Path(__file__).resolve().parent / "weights.npz"


def main() -> None:
    parser = argparse.ArgumentParser(description="Run tiny GPT inference from saved weights")
    parser.add_argument("--weights", type=Path, default=DEFAULT_WEIGHTS)
    parser.add_argument("--prompt", type=str, default="the cat and the")
    parser.add_argument("--max-new-tokens", type=int, default=8)
    args = parser.parse_args()

    if not args.weights.exists():
        raise SystemExit(
            f"Missing weights at {args.weights}. Train first with: python3 train.py"
        )

    model = TinyGPT.load(args.weights)
    ids = model.generate(tokenize(args.prompt), max_new_tokens=args.max_new_tokens, greedy=True)
    print(detokenize(ids))


if __name__ == "__main__":
    main()
