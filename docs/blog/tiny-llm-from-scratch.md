# I Built a Tiny LLM With 20 Words — Here's What Finally Clicked

I use large language models every day. For a long time, though, I was using them the way most engineers do: as a black box with a text API.

That works — until it doesn't. The model hallucinates. It ignores half your prompt. Latency spikes and you can't tell whether the bottleneck is tokenization, attention, or sampling. You know the vocabulary of the field — temperature, context window, embeddings — but not how those pieces actually compose into a forward pass.

So I built the smallest decoder I could still respect: a **20-word GPT-style model** in pure NumPy. No framework magic. No GPU. Just the inference path — the same computational story GPT-4 runs, scaled down until you can read every matrix multiply.

What I want to show here is not a toy for its own sake. It's the minimal architecture that still contains the ideas that matter: tokenization, embeddings, causal self-attention, and autoregressive decoding.

> Full code and pretrained weights: `examples/tiny-llm/`  
> `python3 tiny_llm_inference.py`

---

## Autoregressive Generation Is the Whole Game

At inference time, a language model does one thing repeatedly: produce a distribution over the next token, sample (or argmax) from it, append that token, and continue.

```python
# Autoregressive decoding — the inference loop behind ChatGPT
tokens = tokenize("the cat")
while tokens[-1] != END:
    logits = model.forward(tokens)   # unnormalized scores over the vocabulary
    next_id = int(logits[-1].argmax())  # greedy decoding (temperature = 0)
    tokens.append(next_id)
```

Training fitted the weights so those logits are useful. Inference freezes the weights and just runs this loop. Everything else in the stack — ChatGPT's UI, tool calling, system prompts — sits on top of that mechanism.

This post stays on the inference side. I already trained the model and serialized the parameters to `weights.npz`. You can load them and generate without implementing backpropagation.

---

## Tokenization: Discrete Symbols the Model Can Index

Neural nets operate on tensors, not strings. The first step is a deterministic map from text to integer IDs.

```python
# A deliberately tiny vocabulary so every ID stays interpretable.
# Production tokenizers (BPE, SentencePiece) do the same job at ~32k–100k+ symbols,
# often splitting rare words into subword pieces.
VOCAB = ["the", "cat", "dog", "sat", "mat", "END"]
word_to_id = {w: i for i, w in enumerate(VOCAB)}

def tokenize(text: str) -> list[int]:
    """Whitespace tokenizer. Real LLMs use learned subword vocabularies."""
    return [word_to_id[w] for w in text.split()]

tokenize("the cat sat")  # → [0, 1, 3]
```

Two control tokens appear in almost every practical setup:

- **`END`** — a stop symbol. When the model emits it, the decoding loop terminates.
- **`PAD`** — a padding ID used in training so variable-length sequences can share a batch. At inference you usually don't need it.

My working model uses 20 tokens. That constraint is pedagogical, not architectural — the forward equations don't care whether `|V|` is 20 or 50,000.

---

## Embeddings: Dense Vectors That Carry Semantics

A token ID is a one-hot index in disguise. It has no notion of similarity: ID `1` (`"cat"`) is as unrelated to ID `2` (`"dog"`) as it is to ID `3` (`"sat"`).

An **embedding table** fixes that. Each token maps to a vector in \(\mathbb{R}^{d}\). After training, geometry encodes meaning — cosine similarity rises for tokens that play similar roles.

```python
# Toy 3-D embeddings so you can inspect the values.
# Production models use d_model in the hundreds or thousands (GPT-2 small: 768).
embeddings = {
    "the": [0.1, 0.0, 0.0],
    "cat": [0.0, 0.8, 0.2],
    "dog": [0.0, 0.7, 0.3],  # near "cat" — both animate subjects
    "sat": [0.2, 0.1, 0.9],  # farther — a verb, different role
}

# Shape after lookup for "the cat sat": (sequence_length=3, d_model=3)
vectors = [embeddings[w] for w in "the cat sat".split()]
```

This is also where the classic vector arithmetic lives: \(\text{king} - \text{man} + \text{woman} \approx \text{queen}\). Linear directions in embedding space often correspond to semantic relations learned from co-occurrence statistics.

One subtlety: **position**. Self-attention is permutation-equivariant — without positional information, `"dog bites man"` and `"man bites dog"` look the same. I add a learned positional embedding per index so order enters the representation before attention runs.

---

## Softmax: From Logits to a Categorical Distribution

The language-model head projects the final hidden state to `|V|` logits. Softmax converts those logits into a probability distribution:

\[
p_i = \frac{e^{z_i}}{\sum_j e^{z_j}}
\]

```python
import numpy as np

def softmax(scores: np.ndarray) -> np.ndarray:
    # Subtract max for numerical stability (softmax is invariant to shifts).
    shifted = scores - scores.max()
    exp = np.exp(shifted)
    return exp / exp.sum()

logits = np.array([4.0, 1.0, 0.5])  # scores for mat / sat / dog
probs = softmax(logits)             # ≈ [0.93, 0.05, 0.03]
```

Greedy decoding takes `argmax`. Sampling draws from `probs`. Temperature rescales logits before softmax: lower temperature sharpens the distribution; higher temperature flattens it toward uniform.

If you stop here — last-token embedding → LM head → softmax — you have a context-blind predictor. The token `"the"` at the end of `"the big cat sat on the"` receives the same representation as `"the"` at the end of `"the small dog ran to the"`. Attention exists to break that independence.

---

## Causal Self-Attention: Context Without Looking Ahead

Attention replaces "use the last vector" with "use a **content-dependent weighted sum** of earlier vectors."

Intuition first:

```python
# Desired behavior when predicting after "the cat sat":
# put most mass on "cat", a little on "sat", almost none on "the".
context = (
    0.10 * embeddings["the"]
  + 0.70 * embeddings["cat"]
  + 0.20 * embeddings["sat"]
)
```

Mechanically, each position produces three projections:

- **Query (Q)** — what this position is looking for
- **Key (K)** — what this position offers as a match signal
- **Value (V)** — the information to mix in if matched

Scaled dot-product attention is:

\[
\mathrm{Attention}(Q,K,V) = \mathrm{softmax}\!\left(\frac{QK^\top}{\sqrt{d}} + M\right) V
\]

where \(M\) is a **causal mask** (upper triangle set to \(-\infty\)) so position \(t\) cannot attend to \(t+1, t+2, \ldots\). That constraint is what makes left-to-right generation valid. Without it, the model could leak future tokens into the present — fine for some encoder tasks, fatal for autoregressive decoding.

I use a **single attention head**. Multi-head attention is the same operator in parallel subspaces; it's important at scale, but it obscures the first reading of the math. One head is enough to learn long-range dependencies in this 20-word world — for example, binding `"big"` six tokens earlier to `"mat"`.

---

## The Forward Pass, End to End

Putting the pieces together, inference is three stages:

1. **Embed** token IDs and add positional encodings  
2. **Attend** with a causal mask (residual connection keeps the original signal)  
3. **Project** with the LM head to vocabulary logits, then decode

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

Observed outputs with the shipped weights:

```text
'the cat and the' -> the cat and the dog END
'the big cat sat on the' -> the big cat sat on the big mat END
'the red big cat sat on the' -> the red big cat sat on the big mat END
'the small dog ran to the small' -> the small dog ran to the small house END
```

The `"red big cat"` example is the interesting one. Color was present in training as a distractor; size was predictive. The attention pattern learns to route mass toward `"big"`, not `"red"`. That specialization wasn't coded as a rule — it emerged from the loss landscape.

---

## Annotated Inference Implementation

Below is the complete forward path. Every matrix has a job. Read it top to bottom once; the architecture is intentionally thin so the signal isn't buried under framework boilerplate.

```python
"""
Tiny GPT — inference only.

Computational graph (per decoding step):
  token ids
    → token embedding + positional embedding     (lookup)
    → causal self-attention + residual           (context mixing)
    → LM head                                    (vocab logits)
    → argmax                                     (greedy next token)

Parameters live in weights.npz (trained offline). This file never updates them.
"""

import argparse
from pathlib import Path
import numpy as np

# ---------------------------------------------------------------------------
# Vocabulary
# ---------------------------------------------------------------------------
# Closed world of 20 tokens. Unknown words raise KeyError on purpose —
# a production tokenizer would fall back to subword pieces / <unk>.
VOCAB = [
    "the", "cat", "dog", "sat", "ran", "on", "mat", "house", "a",
    "big", "small", "quickly", "slowly", "and", "is", "red", "blue", "to",
    "PAD", "END",
]
WORD_TO_ID = {w: i for i, w in enumerate(VOCAB)}
ID_TO_WORD = {i: w for i, w in enumerate(VOCAB)}
END = WORD_TO_ID["END"]  # stop token for the decoding loop


def softmax(x: np.ndarray, axis: int = -1) -> np.ndarray:
    """Numerically stable softmax → categorical distribution along `axis`."""
    x = x - np.max(x, axis=axis, keepdims=True)  # prevents exp overflow
    exp = np.exp(x)
    return exp / np.sum(exp, axis=axis, keepdims=True)


def attention(x: np.ndarray, Wq: np.ndarray, Wk: np.ndarray, Wv: np.ndarray) -> np.ndarray:
    """
    Single-head causal self-attention.

    Args:
        x:  (n, d) hidden states for the current prefix
        Wq, Wk, Wv: (d, d) projection matrices learned during training

    Returns:
        (n, d) context vectors — each row is a weighted mix of values
        from positions ≤ that row (causality enforced by the mask).
    """
    # Linear projections: same x, three different learned views.
    Q = x @ Wq  # (n, d) — "what am I searching for?"
    K = x @ Wk  # (n, d) — "how do I present myself to searchers?"
    V = x @ Wv  # (n, d) — "what content do I contribute if selected?"

    d = x.shape[1]
    n = x.shape[0]

    # Compatibility scores. Divide by sqrt(d) so dot products don't grow
    # with dimension and push softmax into saturated (near one-hot) regimes.
    scores = (Q @ K.T) / np.sqrt(d)  # (n, n)

    # Causal mask: position i may attend to j only if j <= i.
    # Setting future scores to a large negative makes softmax ≈ 0 there.
    causal = np.triu(np.full((n, n), -1e9), k=1)
    weights = softmax(scores + causal, axis=-1)  # (n, n), rows sum to 1

    # Mix values with the attention weights.
    return weights @ V  # (n, d)


class TinyGPT:
    """
    Minimal decoder:
      Embed(tokens, positions) → Attention → residual → LM head

    Weight file keys:
      emb  (V, d)   token embedding table
      pos  (L, d)   positional embedding table
      Wq, Wk, Wv (d, d)  attention projections
      head (d, V)   vocabulary projection (ties the model to next-token prediction)
    """

    def __init__(self, weights: dict):
        self.w = weights

    @classmethod
    def load(cls, path: str) -> "TinyGPT":
        """Deserialize a trained checkpoint. Inference never writes back."""
        return cls(dict(np.load(path)))

    def forward(self, ids: np.ndarray) -> np.ndarray:
        """
        Full forward pass for a token prefix.

        Args:
            ids: (n,) int token ids
        Returns:
            logits: (n, V) — row t is P(next token | ids[:t+1]) before softmax
        """
        w = self.w
        n = len(ids)

        # 1. Representation: token identity + absolute position.
        #    Without `pos`, attention cannot distinguish order.
        x = w["emb"][ids] + w["pos"][:n]  # (n, d)

        # 2. Context mixing. Residual connection (x + attn) preserves the
        #    original embedding while allowing attention to add refinements —
        #    the same pattern used inside Transformer blocks at scale.
        x = x + attention(x, w["Wq"], w["Wk"], w["Wv"])

        # 3. Language-model head: hidden state → score for every vocab entry.
        #    We read logits[-1] at generation time (predict given the full prefix).
        return x @ w["head"]  # (n, V)

    def generate(self, prompt: str, max_new: int = 8) -> str:
        """
        Greedy autoregressive decoding.

        At each step we condition on the growing prefix, take argmax over the
        final position's logits, and stop on END (or max_new tokens).
        """
        ids = [WORD_TO_ID[token] for token in prompt.lower().split()]
        for _ in range(max_new):
            logits = self.forward(np.asarray(ids, dtype=np.int64))
            next_id = int(logits[-1].argmax())  # greedy ≡ temperature → 0
            ids.append(next_id)
            if next_id == END:
                break
        return " ".join(ID_TO_WORD[i] for i in ids)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run tiny GPT inference")
    parser.add_argument("--weights", default="weights.npz")
    parser.add_argument("--prompt", default="the cat and the")
    args = parser.parse_args()
    print(TinyGPT.load(args.weights).generate(args.prompt))
```

To regenerate weights from the training set: `python3 train.py --out weights.npz`. For day-to-day experimentation you only need this file plus `weights.npz`.

---

## Same Algorithm, Different Scale

| | This model | Frontier LLMs |
|--|--|--|
| Vocabulary | 20 tokens | \(10^4\)–\(10^5\) BPE/SentencePiece ids |
| Attention | 1 head, 1 layer | many heads × many stacked blocks |
| Width \(d\) | 32 | hundreds → thousands |
| Training | seconds on CPU | enormous GPU/TPU runs |
| Inference | load arrays → forward → argmax | identical control flow |

Depth, width, and data change capacity. They do not change the fact that serving an LLM is repeated next-token prediction under a causal mask.

---

## Why This Mental Model Pays Rent

Once you've implemented the forward pass, a lot of production folklore becomes literal:

- **Hallucination** — decoding maximizes *local linguistic plausibility*, not grounded truth. There is no built-in fact table in the residual stream.
- **Temperature / top-k / top-p** — all of them reshape the categorical distribution before sampling; they don't add knowledge.
- **Context limits** — dense attention is \(O(n^2)\) in sequence length. Memory and latency grow with the prefix you keep.
- **Token billing** — the meter follows the model's true discrete units, which are tokenizer IDs, not whitespace-separated words.

And the training/inference split finally matches how we consume hosted models: somewhere, gradients already ran; what we call every day is a frozen function evaluation.

---

## Where I'd Go Next

- Swap prompts against the shipped checkpoint in `examples/tiny-llm/`
- Inspect attention weights inside `attention()` — print `weights` for `"the big cat sat on the"` and watch mass land on `"big"`
- Re-read [Attention Is All You Need](https://arxiv.org/abs/1706.03762) with this file open; the paper's diagrams map onto functions you can step through

I don't claim this miniature decoder explains every systems trick in a frontier stack. I do claim it's enough to stop treating transformers as metaphysics. They're an autoregressive loop with a particularly effective context mixer in the middle — and once you've written that mixer yourself, the rest of the literature gets much quieter.
