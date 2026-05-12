package io.aicompanion.util;

/**
 * Cheap, dependency-free token estimator. Uses the well-known ~4 chars per
 * token heuristic that holds for English-heavy prompts on Claude/GPT-family
 * BPE tokenisers — good enough for budget tracking and {@code --dry-run-tokens}
 * reporting without dragging in a 50MB tokenizer dependency.
 *
 * <p>For code-heavy inputs the real count tends to be slightly higher
 * (more punctuation), so we round up.
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /** Approximate token count for {@code s}. Null/empty returns 0. */
    public static int estimate(String s) {
        if (s == null || s.isEmpty()) return 0;
        return (s.length() + 3) / 4;
    }
}
