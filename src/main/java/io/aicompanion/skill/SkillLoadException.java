package io.aicompanion.skill;

/**
 * Thrown when a skill cannot be discovered, parsed, or rendered. Unchecked
 * because every call site treats a malformed skill as a fatal user error —
 * there is no recovery path beyond fixing the SKILL.md.
 */
public class SkillLoadException extends RuntimeException {
    public SkillLoadException(String message) { super(message); }
    public SkillLoadException(String message, Throwable cause) { super(message, cause); }
}
