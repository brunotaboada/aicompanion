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
    boolean      testEnabled,
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
    boolean      preCheckTests,
    int          maxTokensPerRun,
    boolean      initInstructions,
    Map<String, SkillConfig> skills
) {

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
}
