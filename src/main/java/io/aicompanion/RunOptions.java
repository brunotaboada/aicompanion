package io.aicompanion;

/**
 * Per-invocation flags for a task run. Distinct from {@link io.aicompanion.config.Config}
 * because these are transient (CLI-only, never persisted in a config file).
 */
public record RunOptions(boolean fresh, boolean retryFailed) {

    public static RunOptions defaults() {
        return new RunOptions(false, false);
    }
}
