# Build a Tiny LLM in Python (20 Words, NumPy Only)

ChatGPT feels like magic until it doesn't. It hallucinates, repeats itself, or ignores something you said three sentences ago. Most of us treat LLMs as black boxes.

They're not. Under the hood, every LLM is **next-word prediction in a loop** — tokenize, embed, attend, predict, repeat.

This post walks through a **tiny GPT with only 20 words**, in pure NumPy. We'll start with one tiny sentence — `"the cat sat"` — and gradually expand to a small vocabulary so each example builds on the last.

> **Runnable code:** `examples/tiny-llm/`  
> `pip install -r requirements.txt && python3 train.py`

---

## What an LLM Actually Does

Given some text, the model guesses the next word. Then it adds that word and guesses again.

```python
words = ["the", "cat"]          # input so far
next_word = model.predict(words) # → "sat"
words.append(next_word)          # ["the", "cat", "sat"]
next_word = model.predict(words) # → "on" (or END to stop)
```

That's the whole loop behind ChatGPT. Training teaches the model good guesses. Inference just runs the loop.

---

## Step 1: Turn Words Into Numbers

Computers need numbers, not words. First we give each word an ID:

```python
VOCAB = ["the", "cat", "dog", "sat", "mat", "END"]  # simplified
word_to_id = {w: i for i, w in enumerate(VOCAB)}

def tokenize(text):
    return [word_to_id[w] for w in text.split()]

tokenize("the cat sat")   # → [0, 1, 3]
```

Our full model uses 20 words. Same idea, bigger list. `END` means "stop generating."

---

## Step 2: Turn Numbers Into Vectors

An ID like `1` doesn't carry meaning. An **embedding** is a short list of numbers that does:

```python
import numpy as np

# Each word → 3 numbers (real models use hundreds or thousands)
embeddings = {
    "the": [0.1, 0.0, 0.0],
    "cat": [0.0, 0.8, 0.2],
    "dog": [0.0, 0.7, 0.3],
    "sat": [0.2, 0.1, 0.9],
    "mat": [0.3, 0.1, 0.8],
}

sentence = "the cat sat"
vectors = np.array([embeddings[w] for w in sentence.split()])
# shape: (3, 3) — three words, three numbers each
```

After training, similar words get similar numbers. `"cat"` and `"dog"` end up close. `"cat"` and `"mat"` end up farther apart.

---

## Step 3: Guess the Next Word

Multiply the last word's vector by a weight matrix. You get a score for each word in the vocabulary. Pick the highest score, or sample from the scores if you want more variety.

```python
def softmax(scores):
    e = np.exp(scores - scores.max())
    return e / e.sum()

# Scores for [mat, sat, dog] after seeing "the cat"
scores = np.array([4.0, 1.0, 0.5])
probs = softmax(scores)
# mat: 93%, sat: 6%, dog: 1%
```

**The problem:** this only looks at one word. `"the"` gets the same guess whether the sentence is `"the cat..."` or `"the dog..."`. We need context.

---

## Step 4: Mix in Context With Attention

Instead of using just the last word, **combine all the words** — but not equally. `"cat"` should matter more than `"the"` when the model is deciding what comes next.

```python
# Hand-picked weights for "the cat sat" → predict next word
weights = np.array([0.1, 0.7, 0.2])   # the, cat, sat

context = (
    0.1 * embeddings["the"] +
    0.7 * embeddings["cat"] +
    0.2 * embeddings["sat"]
)
# context ≈ mostly "cat" → a better clue for what comes next
```

Attention **learns** these weights automatically. Each word asks "who is relevant to me?" and gets an answer via dot products:

```python
# Simplified: 2-word sentence "the cat"
the = embeddings["the"]
cat = embeddings["cat"]

score_the = the @ cat   # how related is "the" to "cat"?
score_cat = cat @ cat   # how related is "cat" to itself?

weights = softmax(np.array([score_the, score_cat]))
context = weights[0] * the + weights[1] * cat
```

For a prompt like `"the cat sat on the"`, the useful clues are `"cat"`, `"sat"`, and `"on"`; the repeated `"the"` tokens carry less meaning. Attention gives the model a way to learn that difference instead of treating every word equally.

---

## Step 5: Wrap It in a Transformer Block

Real models add a few helpers around attention:

```python
x = embeddings_for_sentence

x = x + attention(x)      # mix in context, keep original info
x = layer_norm(x)         # keep numbers in a sane range
scores = x[-1] @ lm_head  # last position → guess next word
```

- `x + attention(x)` — a **residual connection**; don't throw away the original
- `layer_norm` — stops numbers from growing out of control
- `x[-1]` — use the **last position** to predict the next word

Large language models stack many of these blocks. Ours uses one. Same pattern, bigger scale.

---

## Step 6: Train and Generate

Show the model sentences. When it guesses wrong, nudge the weights. Repeat.

```python
# Training data (simplified)
data = [
    "the cat sat on the mat",
    "the dog sat on the mat",
    "the cat and the dog",
]

model = TinyGPT()
for _ in range(500):
    for sentence in data:
        model.learn(sentence)   # predict each next word, adjust weights
```

Generate:

```python
words = ["the", "cat", "and", "the"]
while words[-1] != "END":
    words.append(model.predict(words))

print(" ".join(words))
# the cat and the dog END
```

Run the full version:

```bash
cd examples/tiny-llm
pip install -r requirements.txt
python3 step_by_step.py   # tiny demos
python3 train.py          # train + generate
```

---

## Tiny GPT vs Frontier LLMs

| | This post | Frontier LLMs |
|--|--|--|
| Vocabulary | 20 words | very large tokenizer vocabularies |
| Numbers per token | 64 | hundreds or thousands |
| Transformer blocks | 1 | many stacked blocks |
| Training | seconds on CPU | large-scale GPU training |

Same idea. Different scale.

---

## Why Bother?

Once you see the loop, everyday LLM behavior makes sense:

- **Hallucination** — it predicts plausible words, not facts
- **Temperature** — more randomness in word picking
- **Context limits** — longer text = more computation
- **Token billing** — you pay per token, not per word

---

## Next Steps

- Full code: `examples/tiny-llm/`
- Original paper: [Attention Is All You Need](https://arxiv.org/abs/1706.03762)

LLMs aren't magic. They're a loop that predicts the next word. Build one small enough to see inside, and the black box disappears.
