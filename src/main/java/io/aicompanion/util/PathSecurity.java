package io.aicompanion.util;

import io.aicompanion.skill.SkillLoadException;
import java.nio.file.Path;

/** Guards against path traversal in user-supplied names and skill frontmatter. */
public final class PathSecurity {

    private PathSecurity() {}

    /** Validate a single path segment (feature name, skill name). */
    public static String validateSegment(String name, String label) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(label + " must be non-blank.");
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException(
                label + " must not contain path separators or '..': " + name);
        }
        return name;
    }

    /** Reject absolute paths and {@code ..} segments in a relative path string. */
    public static String validateRelativePath(String relative, String label) {
        if (relative == null || relative.isBlank()) {
            throw new SkillLoadException(label + " must be non-blank.");
        }
        if (relative.startsWith("/") || relative.startsWith("\\")
                || relative.matches("^[A-Za-z]:.*") || relative.contains("..")) {
            throw new SkillLoadException(label + " must be a simple relative path: " + relative);
        }
        return relative;
    }

    /**
     * Ensure {@code relative} resolves inside {@code base} after normalisation.
     * Rejects absolute paths and {@code ..} segments.
     */
    public static Path resolveContained(Path base, String relative, String label) {
        validateRelativePath(relative, label);
        Path baseAbs = base.toAbsolutePath().normalize();
        Path resolved = baseAbs.resolve(relative).normalize();
        if (!resolved.startsWith(baseAbs)) {
            throw new SkillLoadException(
                label + " escapes the allowed directory: " + relative);
        }
        return resolved;
    }
}
