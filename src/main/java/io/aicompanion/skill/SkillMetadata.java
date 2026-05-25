package io.aicompanion.skill;

/**
 * The YAML frontmatter of a {@code SKILL.md} file. Carries everything the
 * {@code skills} command needs to list and describe a skill without rendering
 * its full body — and tells {@link SkillLoader#load} which file to watch for
 * the agent to write as the signal that the chat is done.
 *
 * @param name              skill directory name (e.g. {@code create-prd})
 * @param description       one-line summary shown by the {@code skills} command
 * @param outputRelativePath path the agent writes, relative to {@code features/<feature>/}
 *                          (e.g. {@code _prd.md})
 */
public record SkillMetadata(String name, String description, String outputRelativePath) {}
