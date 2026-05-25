package io.aicompanion.skill;

import java.nio.file.Path;

/**
 * A fully rendered skill, ready to ship as the opening prompt of a chat session.
 * {@link #body} has every {@code {{...}}} placeholder substituted; {@link #outputFile}
 * is the absolute path the chat loop watches for as the "done" signal.
 *
 * @param metadata    parsed frontmatter (name, description, output path)
 * @param body        rendered SKILL.md body with placeholders filled and
 *                    frontmatter stripped
 * @param outputFile  absolute path the agent will write to signal completion
 */
public record Skill(SkillMetadata metadata, String body, Path outputFile) {}
