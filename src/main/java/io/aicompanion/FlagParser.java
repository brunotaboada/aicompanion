package io.aicompanion;

import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for CLI flag → config-key mapping.
 * Previously duplicated between Main (String[] args) and Shell (parseFlags).
 */
public final class FlagParser {

    public record ParseResult(
        Map<String, String> configOverrides,
        boolean nonInteractive,
        boolean fresh,
        boolean retryFailed,
        boolean dryRunTokens
    ) {
        public RunOptions runOptions() {
            return new RunOptions(fresh, retryFailed, dryRunTokens);
        }
    }

    private FlagParser() {}

    /**
     * Parse tokens starting at {@code from}. The token {@code "run"} marks
     * the invocation as non-interactive (headless). Version/help tokens are
     * not handled here — callers that need them should check before calling.
     *
     * @throws IllegalArgumentException on an unknown flag, a stray positional
     *         token, or a value-taking flag with no value — a typo such as
     *         {@code --feature} must not silently run against the defaults
     */
    public static ParseResult parse(String[] tokens, int from) {
        Map<String, String> overrides = new HashMap<>();
        boolean nonInteractive = false;
        boolean fresh          = false;
        boolean retryFailed    = false;
        boolean dryRunTokens   = false;

        for (int i = from; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "run"                   -> nonInteractive = true;
                case "--features"
                    -> overrides.put("features_dir", valueOf(tokens, ++i, "--features"));
                case "--project"
                    -> overrides.put("project_dir", valueOf(tokens, ++i, "--project"));
                case "--agent"
                    -> overrides.put("agent", valueOf(tokens, ++i, "--agent"));
                case "--model"
                    -> overrides.put("model", valueOf(tokens, ++i, "--model"));
                case "--test-command"
                    -> overrides.put("test_command", valueOf(tokens, ++i, "--test-command"));
                case "--verify-commands"
                    -> overrides.put("verify_commands", valueOf(tokens, ++i, "--verify-commands"));
                case "--timeout"
                    -> overrides.put("session_timeout_min", valueOf(tokens, ++i, "--timeout"));
                case "--test-timeout"
                    -> overrides.put("test_timeout_min", valueOf(tokens, ++i, "--test-timeout"));
                case "--report-dir"
                    -> overrides.put("report_dir", valueOf(tokens, ++i, "--report-dir"));
                case "--max-tokens"
                    -> overrides.put("max_tokens_per_run", valueOf(tokens, ++i, "--max-tokens"));
                case "--compact-after"
                    -> overrides.put("compact_after_n_tasks", valueOf(tokens, ++i, "--compact-after"));
                case "--fix-output-lines"
                    -> overrides.put("fix_output_max_lines", valueOf(tokens, ++i, "--fix-output-lines"));
                case "--no-tests"            -> overrides.put("test_enabled",      "false");
                case "--no-stop-on-failure"  -> overrides.put("stop_on_failure",   "false");
                case "--log-thoughts"        -> overrides.put("log_thoughts",      "true");
                case "--no-yolo"             -> overrides.put("yolo",              "false");
                case "--no-reports"          -> overrides.put("report_enabled",    "false");
                case "--pre-check-tests"     -> overrides.put("pre_check_tests",   "true");
                case "--init-instructions"   -> overrides.put("init_instructions", "true");
                case "--task-preamble-strip" -> overrides.put("task_preamble_strip", "true");
                case "--no-init-instructions"    -> overrides.put("init_instructions",   "false");
                case "--no-task-preamble-strip"  -> overrides.put("task_preamble_strip", "false");
                case "--no-pre-check-tests"      -> overrides.put("pre_check_tests",     "false");
                case "--fresh"               -> fresh = true;
                case "--retry-failed"        -> retryFailed = true;
                case "--dry-run-tokens"      -> { dryRunTokens = true; nonInteractive = true; }
                default -> throw new IllegalArgumentException(
                    (tokens[i].startsWith("-") ? "Unknown flag: " : "Unexpected argument: ")
                        + tokens[i] + " (see --help)");
            }
        }
        return new ParseResult(overrides, nonInteractive, fresh, retryFailed, dryRunTokens);
    }

    /** The token at {@code i} as {@code flag}'s value, or an error naming the flag if there is none. */
    private static String valueOf(String[] tokens, int i, String flag) {
        if (i >= tokens.length) {
            throw new IllegalArgumentException("Flag " + flag + " requires a value (see --help)");
        }
        return tokens[i];
    }
}
