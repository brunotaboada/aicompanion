package io.aicompanion.skill;

import java.nio.file.Path;

/**
 * Parser for the per-invocation arguments shared by every skill command:
 * a positional feature name plus optional {@code --seed <path>} and
 * {@code --model <name>} flags. Lives outside {@code FlagParser} because
 * these are one-shot — they affect this single skill run, not the whole
 * config.
 */
public record SkillCommandArgs(String feature, Path seed, String model) {

    /** Parse tokens starting at {@code from}; returns null when no feature was given. */
    public static SkillCommandArgs parse(String[] tokens, int from) {
        String feature = null;
        Path   seed    = null;
        String model   = null;

        for (int i = from; i < tokens.length; i++) {
            String tok = tokens[i];
            switch (tok) {
                case "--seed"  -> { if (i + 1 < tokens.length) seed  = Path.of(tokens[++i]); }
                case "--model" -> { if (i + 1 < tokens.length) model = tokens[++i]; }
                default -> {
                    if (feature == null && !tok.startsWith("--")) feature = tok;
                }
            }
        }
        return new SkillCommandArgs(feature, seed, model);
    }
}
