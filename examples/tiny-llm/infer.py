"""Load pretrained weights and run inference."""

from __future__ import annotations

import argparse
from pathlib import Path

from tiny_llm.model import TinyGPT
from tiny_llm.vocab import detokenize, tokenize

DEFAULT_WEIGHTS = Path(__file__).resolve().parent / "weights.npz"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", type=Path, default=DEFAULT_WEIGHTS)
    parser.add_argument("--prompt", default="the cat and the")
    args = parser.parse_args()

    if not args.weights.exists():
        raise SystemExit(f"Missing {args.weights}. Run: python3 train.py")

    model = TinyGPT.load(args.weights)
    print(detokenize(model.generate(tokenize(args.prompt))))


if __name__ == "__main__":
    main()
