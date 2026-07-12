"""Training sentences that teach size matching, distractors, and variety."""

from __future__ import annotations

from .vocab import END_ID, tokenize


def training_sentences() -> list[list[int]]:
    sentences: list[str] = []

    for size in ("big", "small"):
        for animal in ("cat", "dog"):
            sentences.append(f"the {size} {animal} sat on the {size} mat")
            sentences.append(f"the {size} {animal} ran to the {size} house")
            for color in ("red", "blue"):
                sentences.append(
                    f"the {color} {size} {animal} sat on the {size} mat"
                )
                sentences.append(
                    f"the {color} {size} {animal} ran to the {size} house"
                )

    # Color appears but does not predict the ending.
    sentences.extend(
        [
            "the red cat sat on the mat",
            "the blue dog ran to the house",
            "the red cat sat on the house",
            "the blue dog ran to the mat",
        ]
    )

    # Prefer variety after "and".
    sentences.extend(["the cat and the dog"] * 8)
    sentences.extend(["the dog and the cat"] * 8)
    sentences.extend(["the cat and the cat", "the dog and the dog"])

    # Every training sequence ends with END so generation knows when to stop.
    return [tokenize(s) + [END_ID] for s in sentences]
