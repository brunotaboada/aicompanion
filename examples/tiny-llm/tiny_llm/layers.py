from __future__ import annotations

import numpy as np


def softmax(x: np.ndarray, axis: int = -1) -> np.ndarray:
    x = x - np.max(x, axis=axis, keepdims=True)
    exp = np.exp(x)
    return exp / np.sum(exp, axis=axis, keepdims=True)


def gelu(x: np.ndarray) -> np.ndarray:
    return 0.5 * x * (1.0 + np.tanh(np.sqrt(2.0 / np.pi) * (x + 0.044715 * x**3)))


def gelu_derivative(x: np.ndarray) -> np.ndarray:
  # Derivative of tanh-based GELU approximation.
    inner = np.sqrt(2.0 / np.pi) * (x + 0.044715 * x**3)
    tanh_inner = np.tanh(inner)
    sech2 = 1.0 - tanh_inner**2
    d_inner = np.sqrt(2.0 / np.pi) * (1.0 + 3.0 * 0.044715 * x**2)
    return 0.5 * (1.0 + tanh_inner) + 0.5 * x * sech2 * d_inner


def causal_mask(seq_len: int) -> np.ndarray:
    """0 on/below diagonal, -inf above (can't attend to future tokens)."""
    mask = np.triu(np.ones((seq_len, seq_len), dtype=np.float64), k=1)
    return mask * -1e9


def positional_encoding(seq_len: int, d_model: int) -> np.ndarray:
    position = np.arange(seq_len)[:, None]
    div = np.exp(np.arange(0, d_model, 2) * (-np.log(10000.0) / d_model))
    pe = np.zeros((seq_len, d_model), dtype=np.float64)
    pe[:, 0::2] = np.sin(position * div)
    pe[:, 1::2] = np.cos(position * div)
    return pe


def layer_norm(x: np.ndarray, gamma: np.ndarray, beta: np.ndarray, eps: float = 1e-5):
    mean = x.mean(axis=-1, keepdims=True)
    var = ((x - mean) ** 2).mean(axis=-1, keepdims=True)
    std = np.sqrt(var + eps)
    normalized = (x - mean) / std
    out = gamma * normalized + beta
    cache = (x, normalized, mean, std, gamma)
    return out, cache


def layer_norm_backward(grad_out: np.ndarray, cache) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    x, normalized, mean, std, gamma = cache
    d_norm = grad_out * gamma
    n = x.shape[-1]
    d_var = np.sum(d_norm * (x - mean) * -0.5 * std**-3, axis=-1, keepdims=True)
    d_mean = np.sum(d_norm * -1.0 / std, axis=-1, keepdims=True) + d_var * np.mean(
        -2.0 * (x - mean), axis=-1, keepdims=True
    )
    grad_x = d_norm / std + d_var * 2.0 * (x - mean) / n + d_mean / n
    reduce_axes = tuple(range(x.ndim - 1))
    grad_gamma = np.sum(grad_out * normalized, axis=reduce_axes)
    grad_beta = np.sum(grad_out, axis=reduce_axes)
    return grad_x, grad_gamma, grad_beta


def linear(x: np.ndarray, w: np.ndarray, b: np.ndarray):
    out = x @ w + b
    return out, (x, w)


def linear_backward(grad_out: np.ndarray, cache) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    x, w = cache
    grad_w = x.reshape(-1, x.shape[-1]).T @ grad_out.reshape(-1, grad_out.shape[-1])
    grad_b = grad_out.reshape(-1, grad_out.shape[-1]).sum(axis=0)
    grad_x = grad_out @ w.T
    return grad_x, grad_w, grad_b


def multi_head_attention(
    x: np.ndarray,
    w_q: np.ndarray,
    w_k: np.ndarray,
    w_v: np.ndarray,
    w_o: np.ndarray,
    n_heads: int,
):
    """Scaled dot-product attention with causal masking. x shape: (batch, seq, d_model)."""
    batch, seq_len, d_model = x.shape
    d_head = d_model // n_heads

    q, _ = linear(x, w_q, np.zeros(d_model))
    k, _ = linear(x, w_k, np.zeros(d_model))
    v, _ = linear(x, w_v, np.zeros(d_model))

    def split_heads(t: np.ndarray) -> np.ndarray:
        return t.reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)

    qh, kh, vh = split_heads(q), split_heads(k), split_heads(v)
    scores = qh @ kh.transpose(0, 1, 3, 2) / np.sqrt(d_head)
    scores = scores + causal_mask(seq_len)

    weights = softmax(scores, axis=-1)
    context = weights @ vh
    context = context.transpose(0, 2, 1, 3).reshape(batch, seq_len, d_model)
    out, lin_cache = linear(context, w_o, np.zeros(d_model))

    cache = {
        "x": x,
        "q": q,
        "k": k,
        "v": v,
        "scores": scores,
        "weights": weights,
        "vh": vh,
        "n_heads": n_heads,
        "d_head": d_head,
        "w_q": w_q,
        "w_k": w_k,
        "w_v": w_v,
        "lin_cache": lin_cache,
    }
    return out, cache


def multi_head_attention_backward(grad_out: np.ndarray, cache) -> dict[str, np.ndarray]:
    x = cache["x"]
    batch, seq_len, d_model = x.shape
    n_heads = cache["n_heads"]
    d_head = cache["d_head"]

    grad_context, grad_w_o, _ = linear_backward(grad_out, cache["lin_cache"])
    grad_context = grad_context.reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)
    weights = cache["weights"]
    vh = cache["vh"]

    grad_vh = weights.transpose(0, 1, 3, 2) @ grad_context
    grad_weights = grad_context @ vh.transpose(0, 1, 3, 2)

    scores = cache["scores"]
    grad_scores = weights * (grad_weights - np.sum(grad_weights * weights, axis=-1, keepdims=True))
    grad_scores = grad_scores / np.sqrt(d_head)

    qh = cache["q"].reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)
    kh = cache["k"].reshape(batch, seq_len, n_heads, d_head).transpose(0, 2, 1, 3)

    grad_qh = grad_scores @ kh
    grad_kh = grad_scores.transpose(0, 1, 3, 2) @ qh

    def merge_heads(t: np.ndarray) -> np.ndarray:
        return t.transpose(0, 2, 1, 3).reshape(batch, seq_len, d_model)

    grad_q, grad_w_q, _ = linear_backward(merge_heads(grad_qh), (x, cache["w_q"]))
    grad_k, grad_w_k, _ = linear_backward(merge_heads(grad_kh), (x, cache["w_k"]))
    grad_v, grad_w_v, _ = linear_backward(merge_heads(grad_vh), (x, cache["w_v"]))

    grad_x = grad_q + grad_k + grad_v
    return {
        "grad_x": grad_x,
        "grad_w_q": grad_w_q,
        "grad_w_k": grad_w_k,
        "grad_w_v": grad_w_v,
        "grad_w_o": grad_w_o,
    }


def feed_forward(x: np.ndarray, w1: np.ndarray, b1: np.ndarray, w2: np.ndarray, b2: np.ndarray):
    hidden, cache1 = linear(x, w1, b1)
    activated = gelu(hidden)
    out, cache2 = linear(activated, w2, b2)
    return out, {"hidden": hidden, "activated": activated, "cache1": cache1, "cache2": cache2, "w1": w1, "w2": w2}


def feed_forward_backward(grad_out: np.ndarray, cache) -> dict[str, np.ndarray]:
    grad_act, grad_w2, grad_b2 = linear_backward(grad_out, cache["cache2"])
    grad_hidden = grad_act * gelu_derivative(cache["hidden"])
    grad_x, grad_w1, grad_b1 = linear_backward(grad_hidden, cache["cache1"])
    return {
        "grad_x": grad_x,
        "grad_w1": grad_w1,
        "grad_b1": grad_b1,
        "grad_w2": grad_w2,
        "grad_b2": grad_b2,
    }
