from __future__ import annotations

from pathlib import Path

import numpy as np

from .layers import (
    feed_forward,
    feed_forward_backward,
    layer_norm,
    layer_norm_backward,
    multi_head_attention,
    multi_head_attention_backward,
    positional_encoding,
    softmax,
)
from .vocab import END_ID, PAD_ID, VOCAB_SIZE

WEIGHT_KEYS = (
    "embeddings",
    "ln1_gamma",
    "ln1_beta",
    "ln2_gamma",
    "ln2_beta",
    "w_q",
    "w_k",
    "w_v",
    "w_o",
    "ff_w1",
    "ff_b1",
    "ff_w2",
    "ff_b2",
    "lm_head",
)


class TinyGPTConfig:
    def __init__(
        self,
        vocab_size: int = VOCAB_SIZE,
        d_model: int = 64,
        n_heads: int = 4,
        max_seq_len: int = 16,
        seed: int = 42,
    ):
        self.vocab_size = vocab_size
        self.d_model = d_model
        self.n_heads = n_heads
        self.max_seq_len = max_seq_len
        self.seed = seed


class TinyGPT:
    """Minimal GPT-style decoder with one transformer block (NumPy only)."""

    def __init__(self, config: TinyGPTConfig | None = None):
        self.config = config or TinyGPTConfig()
        self.learning_rate = 0.05
        rng = np.random.default_rng(self.config.seed)
        d = self.config.d_model
        v = self.config.vocab_size
        scale = 0.02

        self.embeddings = rng.normal(0, scale, (v, d))
        self.pos_encoding = positional_encoding(self.config.max_seq_len, d)
        self.ln1_gamma = np.ones(d)
        self.ln1_beta = np.zeros(d)
        self.ln2_gamma = np.ones(d)
        self.ln2_beta = np.zeros(d)
        self.w_q = rng.normal(0, scale, (d, d))
        self.w_k = rng.normal(0, scale, (d, d))
        self.w_v = rng.normal(0, scale, (d, d))
        self.w_o = rng.normal(0, scale, (d, d))
        self.ff_w1 = rng.normal(0, scale, (d, d * 2))
        self.ff_b1 = np.zeros(d * 2)
        self.ff_w2 = rng.normal(0, scale, (d * 2, d))
        self.ff_b2 = np.zeros(d)
        self.lm_head = rng.normal(0, scale, (d, v))

    def forward(self, token_ids: np.ndarray):
        batch, seq_len = token_ids.shape
        x = self.embeddings[token_ids] + self.pos_encoding[:seq_len]
        residual1 = x

        attn_out, attn_cache = multi_head_attention(
            x, self.w_q, self.w_k, self.w_v, self.w_o, self.config.n_heads
        )
        x = residual1 + attn_out
        ln1_out, ln1_cache = layer_norm(x, self.ln1_gamma, self.ln1_beta)
        residual2 = ln1_out

        ff_out, ff_cache = feed_forward(
            ln1_out, self.ff_w1, self.ff_b1, self.ff_w2, self.ff_b2
        )
        x = residual2 + ff_out
        ln2_out, ln2_cache = layer_norm(x, self.ln2_gamma, self.ln2_beta)
        logits = ln2_out @ self.lm_head

        return logits, {
            "token_ids": token_ids,
            "attn_cache": attn_cache,
            "ln1_cache": ln1_cache,
            "ff_cache": ff_cache,
            "ln2_cache": ln2_cache,
            "residual1": residual1,
            "residual2": residual2,
        }

    def backward(self, grad_logits: np.ndarray, cache) -> None:
        token_ids = cache["token_ids"]
        ln2_cache = cache["ln2_cache"]
        normalized = ln2_cache[1]

        grad_lm_head = normalized.reshape(-1, self.config.d_model).T @ grad_logits.reshape(
            -1, self.config.vocab_size
        )
        grad_ln2 = grad_logits @ self.lm_head.T
        grad_x, grad_ln2_gamma, grad_ln2_beta = layer_norm_backward(grad_ln2, ln2_cache)

        grad_residual2 = grad_x
        grad_ff_out = grad_x
        ff_grads = feed_forward_backward(grad_ff_out, cache["ff_cache"])
        grad_ln1_out = ff_grads["grad_x"] + grad_residual2

        grad_x, grad_ln1_gamma, grad_ln1_beta = layer_norm_backward(grad_ln1_out, cache["ln1_cache"])
        grad_residual1 = grad_x
        grad_attn = grad_x
        attn_grads = multi_head_attention_backward(grad_attn, cache["attn_cache"])
        grad_embed = attn_grads["grad_x"] + grad_residual1

        grad_embeddings = np.zeros_like(self.embeddings)
        np.add.at(grad_embeddings, token_ids, grad_embed)

        lr = self.learning_rate
        self.lm_head -= lr * grad_lm_head
        self.ln2_gamma -= lr * grad_ln2_gamma
        self.ln2_beta -= lr * grad_ln2_beta
        self.ln1_gamma -= lr * grad_ln1_gamma
        self.ln1_beta -= lr * grad_ln1_beta
        self.w_q -= lr * attn_grads["grad_w_q"]
        self.w_k -= lr * attn_grads["grad_w_k"]
        self.w_v -= lr * attn_grads["grad_w_v"]
        self.w_o -= lr * attn_grads["grad_w_o"]
        self.ff_w1 -= lr * ff_grads["grad_w1"]
        self.ff_b1 -= lr * ff_grads["grad_b1"]
        self.ff_w2 -= lr * ff_grads["grad_w2"]
        self.ff_b2 -= lr * ff_grads["grad_b2"]
        self.embeddings -= lr * grad_embeddings

    def train_step(self, token_ids: np.ndarray) -> float:
        logits, cache = self.forward(token_ids)
        targets = token_ids[:, 1:]
        pred_logits = logits[:, :-1, :]

        probs = softmax(pred_logits, axis=-1)
        loss = -np.log(
            probs[np.arange(targets.shape[0])[:, None], np.arange(targets.shape[1]), targets] + 1e-9
        ).mean()

        grad_logits = probs.copy()
        batch_idx = np.arange(targets.shape[0])[:, None]
        pos_idx = np.arange(targets.shape[1])
        grad_logits[batch_idx, pos_idx, targets] -= 1
        grad_logits /= targets.size

        full_grad = np.zeros_like(logits)
        full_grad[:, :-1, :] = grad_logits
        self.backward(full_grad, cache)
        return float(loss)

    def generate(
        self,
        prompt_ids: list[int],
        max_new_tokens: int = 8,
        temperature: float = 0.8,
        rng: np.random.Generator | None = None,
        greedy: bool = False,
    ) -> list[int]:
        rng = rng or np.random.default_rng()
        ids = list(prompt_ids)
        for _ in range(max_new_tokens):
            window = np.array([ids[-self.config.max_seq_len :]], dtype=np.int64)
            logits, _ = self.forward(window)
            next_logits = logits[0, -1] / max(temperature, 1e-6)
            if greedy:
                next_id = int(np.argmax(next_logits))
            else:
                probs = softmax(next_logits[None, :], axis=-1)[0]
                next_id = int(rng.choice(len(probs), p=probs))
            ids.append(next_id)
            if next_id == END_ID:
                break
        return ids

    def save(self, path: str | Path) -> None:
        path = Path(path)
        payload = {key: getattr(self, key) for key in WEIGHT_KEYS}
        payload["config"] = np.array(
            [self.config.vocab_size, self.config.d_model, self.config.n_heads, self.config.max_seq_len],
            dtype=np.int64,
        )
        np.savez_compressed(path, **payload)

    @classmethod
    def load(cls, path: str | Path) -> TinyGPT:
        data = np.load(path)
        vocab_size, d_model, n_heads, max_seq_len = (int(x) for x in data["config"])
        model = cls(
            TinyGPTConfig(
                vocab_size=vocab_size,
                d_model=d_model,
                n_heads=n_heads,
                max_seq_len=max_seq_len,
            )
        )
        for key in WEIGHT_KEYS:
            setattr(model, key, data[key])
        model.pos_encoding = positional_encoding(model.config.max_seq_len, model.config.d_model)
        return model


def pad_batch(sequences: list[list[int]], pad_id: int = PAD_ID) -> np.ndarray:
    max_len = max(len(s) for s in sequences)
    batch = np.full((len(sequences), max_len), pad_id, dtype=np.int64)
    for i, seq in enumerate(sequences):
        batch[i, : len(seq)] = seq
    return batch
