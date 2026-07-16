# I Built a Tiny LLM With 20 Words — Here's What Finally Made Sense

I use ChatGPT almost every day. And for a long time, I treated it like a black box.

It writes code. It answers questions. Then it hallucinates something confidently wrong, and I have no idea *why*. Temperature? Context windows? Token counts? I knew the words, but not what was actually happening underneath.

So I decided to build the smallest language model I could still take seriously: **20 words**, pure NumPy, no PyTorch, no GPU. Just enough to see the machinery.

What surprised me wasn't how complicated it was. It was how simple the core idea is once you strip away the scale.

---

## The Whole Trick, in One Sentence

An LLM guesses the next word. Then it adds that word to the input and guesses again. That's it.

```python
words = ["the", "cat"]
next_word = model.predict(words)  # → "sat"
words.append(next_word)           # now: ["the", "cat", "sat"]
# keep going until the model says "END"
```

When ChatGPT writes a paragraph, it's doing this loop hundreds of times. It isn't "thinking" in the human sense. It's predicting, over and over, which word is most likely to come next given everything so far.

Training is how it gets good at those guesses — expensive, done once, offline. **Inference** is what you use every day: frozen weights, same loop, fast answers.

This post is about inference. I already trained a tiny model and saved the weights. You can load them and generate text without touching backpropagation at all.

> Code lives in `examples/tiny-llm/`  
> Run it with: `python3 tiny_llm_inference.py`

---

## Step 1: Words Have to Become Numbers

Computers don't read English. Before anything interesting happens, we need to turn text into IDs.

I started with a tiny vocabulary so the whole thing stays readable:

```python
VOCAB = ["the", "cat", "dog", "sat", "mat", "END"]
word_to_id = {w: i for i, w in enumerate(VOCAB)}

def tokenize(text):
    return [word_to_id[w] for w in text.split()]

tokenize("the cat sat")   # → [0, 1, 3]
```

That's tokenization. Real models use tens of thousands of tokens, often pieces of words (`"unbreakable"` → `["un", "break", "able"]`). Same idea, bigger list.

Two special tokens show up a lot:

- `END` — "I'm done generating"
- `PAD` — filler so short sentences can sit in the same batch during training

My full model has 20 words total. Enough to be interesting. Small enough to hold in your head.

---

## Step 2: Numbers Need Meaning — That's Embeddings

An ID like `1` for `"cat"` is just a label. It doesn't say anything about cats.

So we replace each ID with a short list of numbers — a vector:

```python
embeddings = {
    "the": [0.1, 0.0, 0.0],
    "cat": [0.0, 0.8, 0.2],
    "dog": [0.0, 0.7, 0.3],
    "sat": [0.2, 0.1, 0.9],
}

vectors = [embeddings[w] for w in "the cat sat".split()]
# three words → three little vectors
```

I'm using 3 numbers here so you can see them. Real models use hundreds. GPT-2 small uses 768 per token.

The useful part: after training, **similar words land near each other**. `"cat"` and `"dog"` end up close. `"cat"` and `"sat"` don't. That's why the famous `king - man + woman ≈ queen` demo works in bigger models — the geometry of the space encodes relationships.

At this point `"the cat sat"` is no longer text. It's math the model can work with.

---

## Step 3: From Vectors to a Guess

To predict the next word, the model turns a vector into a score for every word in the vocabulary. Softmax turns those scores into probabilities that add up to 100%:

```python
def softmax(scores):
    e = np.exp(scores - scores.max())
    return e / e.sum()

scores = np.array([4.0, 1.0, 0.5])  # mat, sat, dog
# → mat ~93%, sat ~5%, dog ~3%
```

Pick the highest score (or sample from the distribution), append that word, repeat.

Here's the catch I kept running into when this was *all* the model did:

If you only look at the last word, `"the"` in `"the cat sat on the"` looks identical to `"the"` in `"the dog ran to the"`. Same word, same vector, same prediction. The model has no memory of `"cat"` vs `"dog"`.

You need context. That's what attention is for.

---

## Step 4: Attention — Let Earlier Words Help

The fix sounds almost too obvious: don't use just the last word. **Mix in the earlier ones** — but not equally.

`"cat"` should matter more than `"the"` when you're guessing what comes next:

```python
# Hand-wavy version of what attention learns
context = (
    0.1 * embeddings["the"] +
    0.7 * embeddings["cat"] +
    0.2 * embeddings["sat"]
)
# mostly "cat" — which is what you want
```

In a real model, those weights aren't hand-picked. Each word produces a Query ("what am I looking for?"), a Key ("how should others find me?"), and a Value ("what info do I carry?"). Dot products between Queries and Keys decide who pays attention to whom.

One more detail that matters for generation: a **causal mask**. While predicting word 4, the model isn't allowed to peek at word 5. Otherwise it would cheat.

Once I saw attention as "weighted mix of earlier words," the diagrams stopped feeling mystical.

---

## Step 5: Put It Together and Run It

My tiny model does three things:

1. Look up embeddings (and add a position so word order isn't lost)
2. Run single-head attention so words can share context
3. Score the vocabulary and pick the next word

No multi-head attention. No giant feed-forward net. No GELU. Just enough to generate coherent sentences from a 20-word world.

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

When I run it, I get:

```text
'the cat and the' -> the cat and the dog END
'the big cat sat on the' -> the big cat sat on the big mat END
'the red big cat sat on the' -> the red big cat sat on the big mat END
'the small dog ran to the small' -> the small dog ran to the small house END
```

That third one is my favorite. The model sees `"red"` and ignores it — size (`"big"`) is what mattered in the training patterns, not color. Nobody hardcoded that rule. It fell out of the weights.

---

## The Inference Code

This is the whole forward pass. Load weights, embed, attend, predict, loop.

```python
"""Tiny GPT inference — load weights.npz and generate."""

import argparse
from pathlib import Path
import numpy as np

VOCAB = [
    "the", "cat", "dog", "sat", "ran", "on", "mat", "house", "a",
    "big", "small", "quickly", "slowly", "and", "is", "red", "blue", "to",
    "PAD", "END",
]
WORD_TO_ID = {w: i for i, w in enumerate(VOCAB)}
ID_TO_WORD = {i: w for i, w in enumerate(VOCAB)}
END = WORD_TO_ID["END"]


def softmax(x):
    e = np.exp(x - x.max(axis=-1, keepdims=True))
    return e / e.sum(axis=-1, keepdims=True)


def attention(x, Wq, Wk, Wv):
    """Single-head attention. x: (n_words, d)"""
    Q, K, V = x @ Wq, x @ Wk, x @ Wv
    n = len(x)
    mask = np.triu(np.full((n, n), -1e9), 1)  # no looking ahead
    weights = softmax(Q @ K.T / np.sqrt(x.shape[1]) + mask)
    return weights @ V


class TinyGPT:
    def __init__(self, w):
        self.w = w

    @classmethod
    def load(cls, path):
        return cls(dict(np.load(path)))

    def forward(self, ids):
        w = self.w
        x = w["emb"][ids] + w["pos"][: len(ids)]          # embed + position
        x = x + attention(x, w["Wq"], w["Wk"], w["Wv"])   # mix in context
        return x @ w["head"]                              # score each word

    def generate(self, prompt, max_new=8):
        ids = [WORD_TO_ID[t] for t in prompt.lower().split()]
        for _ in range(max_new):
            next_id = int(self.forward(np.array(ids)).argmax(-1)[-1])
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

If you want to retrain from scratch: `python3 train.py --out weights.npz`. Day to day, you only need inference.

---

## How This Compares to GPT-4

| | My tiny model | GPT-4 |
|--|--|--|
| Words | 20 | ~100,000 tokens |
| Attention | 1 head | many heads, many layers |
| Training | seconds on a laptop | months on huge GPU clusters |
| Inference | load a `.npz` file → generate | same loop, much bigger |

I keep coming back to that last row. The *shape* of the computation is the same. Scale changes the capability. It doesn't change the story.

---

## What Clicked for Me After Building This

A few things I used to hand-wave suddenly had plain explanations:

- **Hallucinations** — the model is optimizing for "what word is plausible next," not "what is true."
- **Temperature** — turn it up and you sample more randomly from the probability distribution; turn it down and it plays it safe.
- **Context limits** — attention compares words to each other; longer prompts cost more memory and compute.
- **Token billing** — APIs charge per token because that's the unit the model actually runs on, not words.

And the training-vs-inference split finally matched how I use ChatGPT: the hard learning already happened somewhere else. I'm just running the frozen result.

---

## If You Want to Go Further

- Play with the code and weights in `examples/tiny-llm/`
- Try your own prompt: `python3 tiny_llm_inference.py --prompt "the dog and the"`
- When the tiny version feels clear, the original paper is much less scary: [Attention Is All You Need](https://arxiv.org/abs/1706.03762)

I didn't walk away from this thinking I understand every detail of frontier models. I walked away finally believing they aren't magic — they're a loop that predicts the next word, with some very clever math in the middle.

Build one small enough to see inside. The black box gets a lot less black.
