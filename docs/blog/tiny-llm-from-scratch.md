# I Built a Tiny LLM With 20 Words — Here's What Finally Clicked

I use ChatGPT every day. For a long time I treated it like a black box: type something in, get something out, hope for the best.

That works until the model invents a fact, ignores half your prompt, or burns tokens for no clear reason. I knew the buzzwords — embeddings, attention, temperature — but I couldn't picture what they *did*.

So I built the smallest language model I still take seriously: **20 words**, pure NumPy, no PyTorch, no GPU. Same ideas as GPT-4. Tiny enough to read every line.

This post is that walkthrough. I'll explain each idea with a small example first, then show how it shows up in code.

> Code + pretrained weights: `examples/tiny-llm/`  
> Run: `python3 tiny_llm_inference.py`

---

## The Big Idea: Guess the Next Word, Over and Over

Here's the entire job of an LLM at inference time:

```text
Input:  the cat
Guess:  sat
Input:  the cat sat
Guess:  on
Input:  the cat sat on
Guess:  the
... keep going until the model says END
```

In code, that loop looks like this:

```python
words = ["the", "cat"]
while words[-1] != "END":
    next_word = model.guess(words)  # pick the most likely next word
    words.append(next_word)
```

That's it. ChatGPT writing a paragraph is this loop running hundreds of times.

**Training** teaches the model good guesses (expensive, done once).  
**Inference** uses those frozen guesses (what this post covers).

I already trained a tiny model and saved it as `weights.npz`. You only need to load it and generate.

---

## Step 1: Turn Words Into IDs

Computers don't understand `"cat"`. They understand numbers.

So we give every word a fixed ID:

```python
VOCAB = ["the", "cat", "dog", "sat", "mat", "END"]
#          0      1      2      3      4      5

def tokenize(text):
    word_to_id = {w: i for i, w in enumerate(VOCAB)}
    return [word_to_id[w] for w in text.split()]

tokenize("the cat sat")
# → [0, 1, 3]
```

**Example**

| Text | Tokens (IDs) |
|------|----------------|
| `the cat sat` | `[0, 1, 3]` |
| `the dog sat` | `[0, 2, 3]` |

Same structure. Different middle ID.

Two special words show up a lot:

- `END` — "stop generating"
- `PAD` — filler used in training so short sentences can share a batch

My full model has 20 words. Real models have tens of thousands, and often split rare words into pieces (`"unbreakable"` → `un` + `break` + `able`). Same idea.

---

## Step 2: Turn IDs Into Meaning (Embeddings)

An ID is just a label. `1` for `"cat"` doesn't mean anything by itself.

So we replace each ID with a short list of numbers — an **embedding**:

```python
embeddings = {
    "the": [0.1, 0.0, 0.0],
    "cat": [0.0, 0.8, 0.2],
    "dog": [0.0, 0.7, 0.3],  # close to cat
    "sat": [0.2, 0.1, 0.9],  # farther away
}
```

**Example — who is similar to whom?**

Imagine plotting those numbers:

- `"cat"` and `"dog"` sit near each other (both animals)
- `"sat"` sits somewhere else (an action)

That's why bigger models can do math like:

```text
king - man + woman ≈ queen
```

The space itself stores relationships.

I'm using 3 numbers here so you can see them. My tiny model uses 32. GPT-2 uses 768. More numbers = more room for nuance.

One more thing: **position**.  
Without it, `"dog bites man"` and `"man bites dog"` look the same — same words, different order. So we also add a position vector ("this is word #1", "this is word #2", ...).

After this step, `"the cat sat"` is no longer text. It's a stack of vectors the model can compute with.

---

## Step 3: Turn Scores Into Probabilities (Softmax)

To guess the next word, the model gives every vocabulary word a score. Softmax turns those scores into percentages that add to 100%.

```python
def softmax(scores):
    # subtract max so big numbers don't explode
    e = np.exp(scores - scores.max())
    return e / e.sum()

scores = np.array([4.0, 1.0, 0.5])  # mat, sat, dog
softmax(scores)
# → mat 93%, sat 5%, dog 3%
```

**Example**

| Next word | Score | Probability |
|-----------|-------|-------------|
| mat | 4.0 | 93% |
| sat | 1.0 | 5% |
| dog | 0.5 | 3% |

Greedy decoding = always pick the top one (`mat`).  
Temperature = how random you're willing to be. Low = safe. High = spicy.

**The problem if we stop here:**  
If the model only looks at the *last* word, then:

```text
"... sat on the"  → only sees "the"
"... ran to the"  → only sees "the"
```

Same last word → same guess. It forgot `"cat"` vs `"dog"`.  
We need a way to bring earlier words back into the decision. That's attention.

---

## Step 4: Attention = "Which Earlier Words Matter?"

Attention is a fancy name for a simple move: **don't use only the last word — mix in earlier ones, with different weights.**

**Example** — predicting after `"the cat sat"`:

```python
# We want most of the signal from "cat"
context = (
    0.10 * embeddings["the"]   # almost ignore
  + 0.70 * embeddings["cat"]   # focus here
  + 0.20 * embeddings["sat"]   # a little
)
```

Think of it like reading a sentence and underlining the useful parts.

How does the model choose the weights automatically? Each word makes three vectors:

| Name | Plain English |
|------|----------------|
| **Query** | "What am I looking for?" |
| **Key** | "What do I offer?" |
| **Value** | "What info do I pass along if chosen?" |

Words with matching Query/Key pairs get high weights. Then we mix their Values.

**Causal mask (important):**  
When predicting word 4, the model cannot peek at word 5+. Otherwise it would cheat.

**Example that sold me on attention**

```text
Prompt:  the big cat sat on the
Output:  big mat

Prompt:  the red big cat sat on the
Output:  big mat   ← ignores "red", keeps "big"
```

Nobody wrote an `if color: ignore` rule. During training, size predicted the ending and color didn't. Attention learned where to look.

---

## Step 5: Put It Together

My tiny model does three steps:

```text
1) Embed words (+ position)
2) Attend to earlier words (with a no-peeking mask)
3) Score the vocabulary and pick the next word
```

Then repeat until `END`.

```python
model = TinyGPT.load("weights.npz")
print(model.generate("the cat and the"))
# → the cat and the dog END
```

```bash
cd examples/tiny-llm
pip install -r requirements.txt
python3 tiny_llm_inference.py
```

What I get:

```text
'the cat and the' -> the cat and the dog END
'the big cat sat on the' -> the big cat sat on the big mat END
'the red big cat sat on the' -> the red big cat sat on the big mat END
'the small dog ran to the small' -> the small dog ran to the small house END
```

---

## The Code, With Comments

This is the full inference path. No training. No backpropagation.  
Story of the program: **load weights → embed → attend → guess → repeat**.

```python
"""
Tiny GPT — inference only.

For each new word we:
  1. look up embeddings (meaning + position)
  2. let words share context with attention
  3. score every vocab word and pick the best one

weights.npz was trained ahead of time. This file only reads it.
"""

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
    parser.add_argument("--weights", default="weights.npz")
    parser.add_argument("--prompt", default="the cat and the")
    args = parser.parse_args()
    print(TinyGPT.load(args.weights).generate(args.prompt))
```

Want to retrain? `python3 train.py --out weights.npz`.  
Day to day: just run inference.

---

## Tiny Model vs GPT-4

| | My model | GPT-4 |
|--|--|--|
| Words / tokens | 20 | ~100,000 |
| Attention | 1 simple head | many heads, many layers |
| Training | seconds on a laptop | months on huge clusters |
| Inference | load file → guess next word | same loop, much bigger |

Same algorithm. Different scale.

---

## What This Cleared Up for Me

Once I could run the loop myself, a few confusing things got simple:

- **Hallucinations** — the model picks *likely* words, not *true* facts.
- **Temperature** — controls how random the next-word pick is.
- **Context limits** — longer text means more pairwise attention work (it grows fast).
- **Token billing** — you pay for model tokens, which aren't always the same as English words.

And the training/inference split finally matched how I use ChatGPT: the hard learning already happened somewhere else. I'm just running the frozen result.

---

## Try It Yourself

- Code + weights: `examples/tiny-llm/`
- Your own prompt: `python3 tiny_llm_inference.py --prompt "the dog and the"`
- When this feels clear, the original paper is much less scary: [Attention Is All You Need](https://arxiv.org/abs/1706.03762)

I didn't finish this project knowing every detail of frontier models. I finished it finally able to say: LLMs aren't magic. They're a next-word loop with a clever way of mixing context.

Build one small enough to see inside. The black box gets a lot less black.
