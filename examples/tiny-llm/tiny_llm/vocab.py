"""20-word vocabulary from the algo.monster tiny GPT course."""

VOCAB = [
    "the", "cat", "dog", "sat", "ran", "on", "mat", "house", "a",
    "big", "small", "quickly", "slowly", "and", "is", "red", "blue", "to",
    "PAD", "END",
]

WORD_TO_ID = {word: i for i, word in enumerate(VOCAB)}
ID_TO_WORD = {i: word for i, word in enumerate(VOCAB)}

PAD_ID = WORD_TO_ID["PAD"]
END_ID = WORD_TO_ID["END"]
VOCAB_SIZE = len(VOCAB)


def tokenize(text: str) -> list[int]:
    """Split on whitespace; reject unknown words (like the course demo)."""
    tokens = []
    for word in text.lower().strip().split():
        if word not in WORD_TO_ID:
            raise ValueError(f"Unknown word {word!r}. Vocabulary has only {VOCAB_SIZE} words.")
        tokens.append(WORD_TO_ID[word])
    return tokens


def detokenize(ids: list[int]) -> str:
    return " ".join(ID_TO_WORD[i] for i in ids)
