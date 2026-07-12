"""Tiny demos that match the blog post (small numbers, easy to read)."""

import numpy as np


def demo_tokenize():
    vocab = ["the", "cat", "dog", "sat", "mat", "END"]
    word_to_id = {w: i for i, w in enumerate(vocab)}
    ids = [word_to_id[w] for w in "the cat sat".split()]
    print("tokenize('the cat sat') ->", ids)


def demo_embeddings():
    embeddings = {
        "the": np.array([0.1, 0.0, 0.0]),
        "cat": np.array([0.0, 0.8, 0.2]),
        "dog": np.array([0.0, 0.7, 0.3]),
        "sat": np.array([0.2, 0.1, 0.9]),
    }
    cat, dog = embeddings["cat"], embeddings["dog"]
    print("cat vector:", cat)
    print("dog vector:", dog)
    print("cat·dog =", float(cat @ dog), "(similar)")
    print("cat·sat =", float(cat @ embeddings["sat"]), "(less similar)")


def demo_softmax():
    def softmax(scores):
        e = np.exp(scores - scores.max())
        return e / e.sum()

    scores = np.array([4.0, 1.0, 0.5])  # mat, sat, dog
    probs = softmax(scores)
    for word, p in zip(["mat", "sat", "dog"], probs):
        print(f"  {word}: {p:.0%}")


def demo_attention():
    embeddings = {
        "the": np.array([0.1, 0.0, 0.0]),
        "cat": np.array([0.0, 0.8, 0.2]),
    }

    # Manual weights: "cat" matters most
    weights = np.array([0.1, 0.9])
    context = weights[0] * embeddings["the"] + weights[1] * embeddings["cat"]
    print("weights: the=10%, cat=90%")
    print("context vector:", context.round(2))


if __name__ == "__main__":
    print("=== tokenize ===")
    demo_tokenize()
    print("\n=== embeddings ===")
    demo_embeddings()
    print("\n=== softmax ===")
    demo_softmax()
    print("\n=== attention ===")
    demo_attention()
