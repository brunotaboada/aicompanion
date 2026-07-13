package io.aicompanion;

/**
 * Outcome of a {@link TaskRunner#run()} invocation. Drives process exit codes in
 * one-shot {@code aicompanion run} mode.
 */
public record RunResult(Outcome outcome, int tasksFailed) {

    public enum Outcome {
        /** Every executed task passed verification. */
        SUCCESS,
        /** One or more tasks failed tests / agent errors. */
        FAILURE,
        /** User interrupted the run (Ctrl+C). */
        ABORTED,
        /** No task files found under {@code features_dir}. */
        NO_TASKS,
        /** Every task was already passed — nothing executed. */
        NOTHING_TO_DO,
        /** Token budget exceeded before all tasks finished. */
        BUDGET_EXCEEDED
    }

    public static RunResult success() {
        return new RunResult(Outcome.SUCCESS, 0);
    }

    /** Exit code for CI: 0 on success/no-op, 1 on failure or abort. */
    public int exitCode() {
        return switch (outcome) {
            case SUCCESS, NO_TASKS, NOTHING_TO_DO -> 0;
            case FAILURE, ABORTED, BUDGET_EXCEEDED -> 1;
        };
    }
}
