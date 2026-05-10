package io.aicompanion.config;

import java.util.List;

public record Config(
    String       agent,
    String       model,
    List<String> agentExtraArgs,
    String       featuresDir,
    String       tasksDir,
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
    boolean      yolo
) {}
