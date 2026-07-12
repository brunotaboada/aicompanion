# Build a Tiny LLM in Python (20 Words, NumPy Only)

ChatGPT feels like magic until it doesn't. It hallucinates, repeats itself, or ignores something you said three sentences ago. Most of us treat LLMs as black boxes.

They're not. Under the hood, every LLM is **next-word prediction in a loop** — tokenize, embed, attend, predict, repeat. The architecture behind GPT-4 is the same one you'll build here, just scaled up.

This post walks through a **tiny GPT with only 20 words**, implemented in pure NumPy. No PyTorch, no GPU, no ML PhD. By the end you'll have runnable code that tokenizes text, runs attention, and generates sentences.

> **Runnable code:** [`examples/tiny-llm/`](../../examples/tiny-llm/) in this repo.  
> `pip install -r requirements.txt && python3 train.py`

Inspired by the interactive course at [algo.monster/courses/llm](https://algo.monster/courses/llm/llm_course_introduction).

---

## What an LLM Actually Does

Type `"The capital of France is"` and you get `"Paris"`. The model isn't looking up facts — it's assigning a probability to every word in its vocabulary, picking one, appending it, and repeating.

```python
# Pseudocode for the entire generation loop
tokens = tokenize("The capital of France is")
while not done:
    logits = model(tokens)          # scores for every word
    probs = softmax(logits[-1])     # last position = next word
    next_token = sample(probs)
    tokens.append(next_token)
```

Training (expensive, done once) adjusts the model's weights. Inference (what you use daily) just runs this loop with frozen weights.

---

## Step 1: Vocabulary and Tokenization

Our model knows exactly 20 words:

```python
VOCAB = [
    "the", "cat", "dog", "sat", "ran", "on", "mat", "house", "a",
    "big", "small", "quickly", "slowly", "and", "is", "red", "blue", "to",
    "PAD", "END",
]
WORD_TO_ID = {w: i for i, w in enumerate(VOCAB)}

def tokenize(text: str) -> list[int]:
    return [WORD_TO_ID[w] for w in text.lower().split()]
```

- `PAD` — pads shorter sequences so we can batch them
- `END` — tells the model to stop generating

Real LLMs use **subword tokenization** (BPE) so `"unbreakable"` becomes `["un", "break", "able"]`. Same idea, bigger vocabulary (~50k tokens).

---

## Step 2: Embeddings — Words as Vectors

A token ID is just an index. An **embedding** is a vector of numbers that captures meaning:

```python
import numpy as np

VOCAB_SIZE = 20
D_MODEL = 64  # GPT-2 small uses 768; we use 64

rng = np.random.default_rng(42)
embeddings = rng.normal(0, 0.02, (VOCAB_SIZE, D_MODEL))

# Look up vectors for "the cat sat"
ids = tokenize("the cat sat")
vectors = embeddings[ids]   # shape: (3, 64)
```

Similar words end up with similar vectors after training. That's why `king - man + woman ≈ queen` works in large models.

---

## Step 3: Softmax — Scores to Probabilities

Matrix multiplication turns an embedding into a score per vocabulary word. Softmax converts those scores into probabilities:

```python
def softmax(x):
    x = x - np.max(x)
    e = np.exp(x)
    return e / e.sum()

scores = np.array([2.3, 5.1, -1.2, 0.4])  # mat, floor, banana, rug
probs = softmax(scores)
# floor: 93%, mat: 6%, rug: 1%, banana: 0.2%
```

This is the prediction step. A weight matrix `lm_head` of shape `(D_MODEL, VOCAB_SIZE)` does the `embedding @ lm_head → scores` part.

**Problem:** if you only use the last word's embedding, `"the"` gets the same prediction whether the sentence is `"the big cat..."` or `"the small dog..."`. You need context.

---

## Step 4: Attention — Let Words Talk to Each Other

Instead of averaging all word vectors equally, **attention** learns which words matter:

```python
def causal_mask(seq_len):
    """Prevent attending to future tokens during generation."""
    return np.triu(np.ones((seq_len, seq_len)), k=1) * -1e9

def positional_encoding(seq_len, d_model):
    pe = np.zeros((seq_len, d_model))
    position = np.arange(seq_len)[:, None]
    div = np.exp(np.arange(0, d_model, 2) * (-np.log(10000.0) / d_model))
    pe[:, 0::2] = np.sin(position * div)
    pe[:, 1::2] = np.cos(position * div)
    return pe

# Each word gets Query, Key, Value vectors
Q = x @ W_q
K = x @ W_k
V = x @ W_v

scores = Q @ K.T / np.sqrt(d_model) + causal_mask(len(x))
weights = softmax(scores, axis=-1)   # how much each word attends to others
output = weights @ V                 # context-aware representation
```

For `"the big cat sat on the"`:
- The last `"the"` attends heavily to `"big"` and `"cat"`
- It ignores `"red"` when that's just a distractor
- Result: predicts `"big mat"`, not `"small house"`

**Multi-head attention** runs several of these patterns in parallel (grammar, semantics, position). Our model uses 4 heads.

---

## Step 5: The Transformer Block

Attention alone isn't enough. Real transformers wrap it with:

```python
# 1. Attention + residual connection
x = x + attention(x)

# 2. Layer norm (keeps values stable)
x = layer_norm(x)

# 3. Feed-forward network + residual
x = x + feed_forward(x)

# 4. Layer norm again
x = layer_norm(x)

# 5. Predict next word
logits = x @ lm_head
```

- **Residual connections** (`x + f(x)`) preserve information as it flows through layers
- **Layer normalization** keeps numbers in a consistent range
- **Feed-forward network** adds non-linearity (matters at GPT scale; included here for completeness)

Stack this block, add positional encoding at the input, and you have a transformer.

---

## Step 6: Train and Generate

Training is next-token prediction on every position: given `"the big cat sat"`, predict `"big"`, then `"cat"`, then `"sat"`, etc.

```python
from tiny_llm.data import training_sentences
from tiny_llm.model import TinyGPT, pad_batch

model = TinyGPT()
sequences = training_sentences()  # course-style patterns

for epoch in range(500):
    batch = pad_batch(sequences)
    loss = model.train_step(batch)  # forward + backward + weight update
```

The training data encodes three behaviors (none of them hard-coded):

| Pattern | Example | What the model learns |
|---------|---------|----------------------|
| Size matching | `the big cat sat on the big mat` | `big` pairs with `big` |
| Distractors | `the red big cat sat on the big mat` | ignore `red`, keep `big` |
| Variety | `the cat and the dog` (×8) vs `the cat and the cat` (×1) | prefer `dog` after `cat and the` |

Generate text:

```python
from tiny_llm.vocab import tokenize, detokenize

prompt = "the cat and the"
ids = model.generate(tokenize(prompt), max_new_tokens=6, greedy=True)
print(detokenize(ids))
# the cat and the dog END
```

Run the full demo:

```bash
cd examples/tiny-llm
pip install -r requirements.txt
python3 step_by_step.py   # softmax, embeddings, attention weights
python3 train.py          # train + generate
```

---

## What You Get vs GPT-4

| | Tiny GPT (this post) | GPT-4 |
|--|--|--|
| Vocabulary | 20 words | ~100k tokens |
| Embedding dim | 64 | thousands |
| Layers | 1 | 120+ (rumored) |
| Attention heads | 4 | thousands |
| Training | seconds on CPU | months on thousands of GPUs |

Same architecture. Different scale.

---

## Why This Matters for Your Day Job

Once you see the machinery:

- **Hallucination** — the model predicts plausible text, not verified facts
- **Temperature** — scales logits before softmax; low = safe, high = random
- **Context limits** — attention is O(n²) in sequence length
- **Token costs** — APIs charge per token, not per word
- **Prompt engineering** — you're shaping inputs so next-word prediction lands where you want

---

## Next Steps

- Full step-by-step course with browser demos: [algo.monster/courses/llm](https://algo.monster/courses/llm/llm_course_introduction)
- Code in this repo: [`examples/tiny-llm/`](../../examples/tiny-llm/)
- Go deeper: read [Attention Is All You Need](https://arxiv.org/abs/1706.03762) — it'll make sense now

LLMs aren't magic. They're matrix math that predicts the next word. Once you build one yourself, the black box disappears.
