package io.aicompanion;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FlagParserTest {

    @Test
    void runTokenMakesNonInteractive() {
        FlagParser.ParseResult r = FlagParser.parse(new String[]{"run"}, 0);
        assertTrue(r.nonInteractive());
        assertFalse(r.fresh());
        assertFalse(r.retryFailed());
        assertFalse(r.dryRunTokens());
    }

    @Test
    void featuresAndProjectFlags() {
        FlagParser.ParseResult r = FlagParser.parse(
            new String[]{"run", "--features", "myfeatures", "--project", "/tmp/proj"}, 0);
        assertTrue(r.nonInteractive());
        assertEquals("myfeatures", r.configOverrides().get("features_dir"));
        assertEquals("/tmp/proj",  r.configOverrides().get("project_dir"));
    }

    @Test
    void booleanToggleFlags() {
        FlagParser.ParseResult r = FlagParser.parse(
            new String[]{"run", "--no-tests", "--no-stop-on-failure", "--log-thoughts", "--no-yolo"}, 0);
        assertEquals("false", r.configOverrides().get("test_enabled"));
        assertEquals("false", r.configOverrides().get("stop_on_failure"));
        assertEquals("true",  r.configOverrides().get("log_thoughts"));
        assertEquals("false", r.configOverrides().get("yolo"));
    }

    @Test
    void freshAndRetryFailedFlags() {
        FlagParser.ParseResult r = FlagParser.parse(new String[]{"run", "--fresh", "--retry-failed"}, 0);
        assertTrue(r.fresh());
        assertTrue(r.retryFailed());
        assertTrue(r.runOptions().fresh());
        assertTrue(r.runOptions().retryFailed());
    }

    @Test
    void dryRunTokensSetsBothFlags() {
        FlagParser.ParseResult r = FlagParser.parse(new String[]{"--dry-run-tokens"}, 0);
        assertTrue(r.dryRunTokens());
        assertTrue(r.nonInteractive());
    }

    @Test
    void agentAndModelFlags() {
        FlagParser.ParseResult r = FlagParser.parse(
            new String[]{"run", "--agent", "claude", "--model", "sonnet"}, 0);
        assertEquals("claude", r.configOverrides().get("agent"));
        assertEquals("sonnet", r.configOverrides().get("model"));
    }

    @Test
    void numericFlags() {
        FlagParser.ParseResult r = FlagParser.parse(
            new String[]{"run", "--max-tokens", "500000", "--compact-after", "5",
                         "--compact-at-context-pct", "60",
                         "--fix-output-lines", "50"}, 0);
        assertEquals("500000", r.configOverrides().get("max_tokens_per_run"));
        assertEquals("5",      r.configOverrides().get("compact_after_n_tasks"));
        assertEquals("60",     r.configOverrides().get("compact_at_context_pct"));
        assertEquals("50",     r.configOverrides().get("fix_output_max_lines"));
    }

    @Test
    void featureFlags() {
        FlagParser.ParseResult r = FlagParser.parse(
            new String[]{"run", "--pre-check-tests", "--init-instructions", "--task-preamble-strip"}, 0);
        assertEquals("true", r.configOverrides().get("pre_check_tests"));
        assertEquals("true", r.configOverrides().get("init_instructions"));
        assertEquals("true", r.configOverrides().get("task_preamble_strip"));
    }

    @Test
    void optOutFlagsDisableLeanDefaults() {
        FlagParser.ParseResult r = FlagParser.parse(
            new String[]{"run", "--no-init-instructions", "--no-task-preamble-strip"}, 0);
        assertEquals("false", r.configOverrides().get("init_instructions"));
        assertEquals("false", r.configOverrides().get("task_preamble_strip"));
    }

    @Test
    void fromNonZeroIndex() {
        FlagParser.ParseResult r = FlagParser.parse(
            new String[]{"run", "--agent", "gemini"}, 1);
        assertFalse(r.nonInteractive());
        assertEquals("gemini", r.configOverrides().get("agent"));
    }

    @Test
    void unknownFlagsAreIgnored() {
        FlagParser.ParseResult r = FlagParser.parse(new String[]{"--unknown-flag", "value"}, 0);
        assertTrue(r.configOverrides().isEmpty());
        assertFalse(r.nonInteractive());
    }

    @Test
    void emptyArgsProduceDefaults() {
        FlagParser.ParseResult r = FlagParser.parse(new String[]{}, 0);
        assertFalse(r.nonInteractive());
        assertTrue(r.configOverrides().isEmpty());
        assertFalse(r.runOptions().fresh());
        assertFalse(r.runOptions().retryFailed());
        assertFalse(r.runOptions().dryRunTokens());
    }

    @Test
    void flagWithMissingValueIsSkipped() {
        // --features at end of args — no following value token
        FlagParser.ParseResult r = FlagParser.parse(new String[]{"run", "--features"}, 0);
        assertFalse(r.configOverrides().containsKey("features_dir"));
    }
}
