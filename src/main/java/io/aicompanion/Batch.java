package io.aicompanion;

import java.nio.file.Path;
import java.util.List;

/**
 * One feature's worth of task files. {@code featureName} is the subdirectory
 * name under {@code features_dir}; it is used as a state-key prefix so resume
 * can distinguish "feature-a/01-task.md" from "feature-b/01-task.md".
 */
public record Batch(String featureName, List<Path> taskPaths) {
    public String stateKeyPrefix() { return featureName + "/"; }
    public String stateKeyFor(Path taskPath) { return stateKeyPrefix() + taskPath.getFileName(); }
}
