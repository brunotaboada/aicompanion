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
                case "--features"            -> { if (i + 1 < tokens.length) overrides.put("features_dir",         tokens[++i]); }
                case "--project"             -> { if (i + 1 < tokens.length) overrides.put("project_dir",          tokens[++i]); }
                case "--agent"               -> { if (i + 1 < tokens.length) overrides.put("agent",                tokens[++i]); }
                case "--model"               -> { if (i + 1 < tokens.length) overrides.put("model",                tokens[++i]); }
                case "--test-command"        -> { if (i + 1 < tokens.length) overrides.put("test_command",         tokens[++i]); }
                case "--verify-commands"     -> { if (i + 1 < tokens.length) overrides.put("verify_commands",      tokens[++i]); }
                case "--timeout"             -> { if (i + 1 < tokens.length) overrides.put("session_timeout_min",  tokens[++i]); }
                case "--test-timeout"        -> { if (i + 1 < tokens.length) overrides.put("test_timeout_min",     tokens[++i]); }
                case "--report-dir"          -> { if (i + 1 < tokens.length) overrides.put("report_dir",           tokens[++i]); }
                case "--max-tokens"          -> { if (i + 1 < tokens.length) overrides.put("max_tokens_per_run",   tokens[++i]); }
                case "--compact-after"       -> { if (i + 1 < tokens.length) overrides.put("compact_after_n_tasks", tokens[++i]); }
                case "--compact-at-context-pct" -> { if (i + 1 < tokens.length) overrides.put("compact_at_context_pct", tokens[++i]); }
                case "--fix-output-lines"    -> { if (i + 1 < tokens.length) overrides.put("fix_output_max_lines", tokens[++i]); }
                case "--no-tests"            -> overrides.put("test_enabled",      "false");
                case "--no-stop-on-failure"  -> overrides.put("stop_on_failure",   "false");
                case "--log-thoughts"        -> overrides.put("log_thoughts",      "true");
                case "--no-yolo"             -> overrides.put("yolo",              "false");
                case "--no-reports"          -> overrides.put("report_enabled",    "false");
                case "--pre-check-tests"     -> overrides.put("pre_check_tests",   "true");
                case "--init-instructions"   -> overrides.put("init_instructions", "true");
                case "--no-init-instructions" -> overrides.put("init_instructions", "false");
                case "--task-preamble-strip" -> overrides.put("task_preamble_strip", "true");
                case "--no-task-preamble-strip" -> overrides.put("task_preamble_strip", "false");
                case "--fresh"               -> fresh = true;
                case "--retry-failed"        -> retryFailed = true;
                case "--dry-run-tokens"      -> { dryRunTokens = true; nonInteractive = true; }
                default -> { /* ignore unknown flags */ }
            }
        }
        return new ParseResult(overrides, nonInteractive, fresh, retryFailed, dryRunTokens);
    }
}
