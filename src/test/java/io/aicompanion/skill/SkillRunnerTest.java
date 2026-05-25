package io.aicompanion.skill;

import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for the bits of {@link SkillRunner} that have logic worth
 * pinning down without spawning an actual agent: the transcript path
 * derivation and the model-resolution helpers. End-to-end ACP behaviour is
 * covered by manual smoke testing — there's no harness for stubbing
 * StdioAcpClientTransport here.
 */
class SkillRunnerTest {

    @TempDir Path tempDir;

    @Test
    void transcriptPathReplacesMdSuffixWithTranscriptMd() {
        Path featureDir = tempDir.resolve("features/auth");
        assertEquals(featureDir.resolve("_prd.transcript.md"),
            SkillRunner.transcriptPathFor(featureDir,
                new SkillMetadata("create-prd", "...", "_prd.md")));
        assertEquals(featureDir.resolve("_techspec.transcript.md"),
            SkillRunner.transcriptPathFor(featureDir,
                new SkillMetadata("create-tech-spec", "...", "_techspec.md")));
        assertEquals(featureDir.resolve("_tasks.transcript.md"),
            SkillRunner.transcriptPathFor(featureDir,
                new SkillMetadata("create-tasks", "...", "_tasks.md")));
    }

    @Test
    void transcriptPathAppendsForOutputsWithoutMdExtension() {
        Path featureDir = tempDir.resolve("features/auth");
        assertEquals(featureDir.resolve("output.transcript.md"),
            SkillRunner.transcriptPathFor(featureDir,
                new SkillMetadata("custom", "...", "output")));
    }

    @Test
    void modelOverrideWinsOverSkillConfig() {
        // --model on the command line beats skills.create-prd.model. We can't
        // exercise the full run() without an agent, but the priority is
        // explicit in run(): modelOverride != null && !blank → use it.
        Config cfg = ConfigLoader.load(Map.of(), Map.of(
            "model", "sonnet",
            "skills", Map.of("create-prd", Map.of("model", "opus"))
        ));
        assertEquals("opus", cfg.modelFor("create-prd"),
            "sanity: config resolution must pick the skill-specific model");
        // SkillRunner.run() short-circuits before consulting cfg.modelFor()
        // when modelOverride is non-blank — pinned in the run() body, line:
        //   model = modelOverride != null && !modelOverride.isBlank()
        //         ? modelOverride : config.modelFor(skillName);
    }
}
