package io.aicompanion.config;

import java.util.List;
import java.util.Map;

public record Config(
    String       agent,
    String       model,
    List<String> agentExtraArgs,
    String       featuresDir,
    List<String> taskExtensions,
    String       taskSort,
    String       projectDir,
    String       testCommand,
    List<String> verifyCommands,
    boolean      testEnabled,
    int          testTimeoutMin,
    boolean      stopOnFailure,
    int          maxFixAttempts,
    int          sessionTimeoutMin,
    boolean      reuseSession,
    String       reportDir,
    boolean      reportEnabled,
    boolean      logToolCalls,
    boolean      logThoughts,
    boolean      yolo,
    int          fixOutputMaxLines,
    boolean      taskPreambleStrip,
    int          compactAfterNTasks,
    int          compactAtContextPct,
    boolean      preCheckTests,
    int          maxTokensPerRun,
    boolean      initInstructions,
    Map<String, SkillConfig> skills
) {

    /** All settable config keys, in display order. Single source of truth for shells and completers. */
    public static final List<String> KEYS = List.of(
        "agent", "model", "agent_extra_args", "features_dir",
        "task_extensions", "task_sort", "project_dir", "test_command",
        "verify_commands", "test_enabled", "test_timeout_min", "stop_on_failure", "max_fix_attempts", "session_timeout_min",
        "reuse_session", "report_dir", "report_enabled", "log_tool_calls",
        "log_thoughts", "yolo",
        "fix_output_max_lines", "task_preamble_strip", "compact_after_n_tasks",
        "compact_at_context_pct",
        "pre_check_tests", "max_tokens_per_run", "init_instructions"
    );

    /** Per-skill overrides. Room to grow beyond {@code model} as new tunables appear. */
    public record SkillConfig(String model) {}

    /**
     * Resolve the model for a skill: skill-specific override wins, otherwise the
     * global {@link #model()} setting. Returns {@code null} when neither is set —
     * callers fall back to the agent's own default.
     */
    public String modelFor(String skillName) {
        SkillConfig sc = skills.get(skillName);
        if (sc != null && sc.model() != null && !sc.model().isBlank()) return sc.model();
        return model;
    }

    /**
     * Commands run (in order) to verify a task: {@code verify_commands} when
     * set, otherwise the single {@code test_command}. Empty when neither is
     * configured — callers treat that as "nothing to verify".
     */
    public List<String> effectiveVerifyCommands() {
        List<String> cleaned = verifyCommands == null ? List.of()
            : verifyCommands.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (!cleaned.isEmpty()) return cleaned;
        if (testCommand == null || testCommand.isBlank()) return List.of();
        return List.of(testCommand);
    }
}
