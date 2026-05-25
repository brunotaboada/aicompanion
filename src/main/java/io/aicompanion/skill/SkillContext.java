package io.aicompanion.skill;

import java.nio.file.Path;

/**
 * Per-invocation inputs for {@link SkillLoader#load}: which feature, which seed
 * file (if any), and whether the skill's output already exists (update mode).
 *
 * @param featureName  feature slug — substituted into {@code {{feature}}}
 * @param featureDir   {@code features/<feature>/} — substituted into {@code {{feature_dir}}}
 * @param seedFile     optional pre-existing notes file to feed into the conversation;
 *                     defaults to {@code featureDir/_idea.md} when null
 * @param updateMode   true when the skill's output file already exists and must
 *                     be revised rather than recreated
 */
public record SkillContext(
    String  featureName,
    Path    featureDir,
    Path    seedFile,
    boolean updateMode
) {}
