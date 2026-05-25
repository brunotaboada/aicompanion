package io.aicompanion.skill;

import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeaturePipelineTest {

    @TempDir Path tempDir;

    /**
     * Build a skill loader rooted at a temp .agents/skills/ directory with all
     * three pipeline skills scaffolded.
     */
    private SkillLoader scaffoldSkillsRoot() throws IOException {
        Path root = tempDir.resolve(".agents/skills");
        writeSkill(root, "create-prd",       "_prd.md");
        writeSkill(root, "create-tech-spec", "_techspec.md");
        writeSkill(root, "create-tasks",     "_tasks.md");
        return new SkillLoader(root);
    }

    private static void writeSkill(Path root, String name, String output) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("SKILL.md"),
            "---\ndescription: " + name + "\noutput: " + output + "\n---\n# " + name);
    }

    private Config configWithFeaturesAt(Path featuresDir) {
        return ConfigLoader.load(Map.of("features_dir", featuresDir.toString()));
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    void runsAllThreeSkillsInOrderUnderAuto() throws IOException {
        Path featuresDir = tempDir.resolve("features");
        SkillLoader loader = scaffoldSkillsRoot();
        Config cfg = configWithFeaturesAt(featuresDir);

        List<String> invocations = new ArrayList<>();
        FeaturePipeline.SkillInvoker invoker = (name, feature, seed, model) -> {
            invocations.add(name);
            Files.createDirectories(featuresDir.resolve(feature));
            // simulate the agent writing the output
            String outputName = switch (name) {
                case "create-prd"       -> "_prd.md";
                case "create-tech-spec" -> "_techspec.md";
                case "create-tasks"     -> "_tasks.md";
                default -> throw new IllegalStateException(name);
            };
            Files.writeString(featuresDir.resolve(feature).resolve(outputName), "generated");
            return ChatLoop.Outcome.COMPLETED;
        };

        FeaturePipeline.GateAsker neverAsked = (a, b, c) -> {
            fail("gate must not fire when --auto is set");
            return null;
        };
        FeaturePipeline pipeline = new FeaturePipeline(cfg, loader, invoker, neverAsked);

        FeaturePipeline.Outcome outcome = pipeline.run("auth",
            new FeaturePipeline.Options(/*auto*/ true, /*force*/ false, null));

        assertEquals(FeaturePipeline.Outcome.ALL_COMPLETED, outcome);
        assertEquals(List.of("create-prd", "create-tech-spec", "create-tasks"), invocations);
    }

    @Test
    void resumesBySkippingStepsWithExistingOutput() throws IOException {
        Path featuresDir = tempDir.resolve("features");
        SkillLoader loader = scaffoldSkillsRoot();
        Config cfg = configWithFeaturesAt(featuresDir);

        // Pre-create _prd.md so step 1 should be skipped.
        Path feat = Files.createDirectories(featuresDir.resolve("auth"));
        Files.writeString(feat.resolve("_prd.md"), "already done");

        List<String> invocations = new ArrayList<>();
        FeaturePipeline.SkillInvoker invoker = (name, feature, seed, model) -> {
            invocations.add(name);
            Files.writeString(feat.resolve(switch (name) {
                case "create-tech-spec" -> "_techspec.md";
                case "create-tasks"     -> "_tasks.md";
                default -> throw new IllegalStateException(name);
            }), "generated");
            return ChatLoop.Outcome.COMPLETED;
        };

        FeaturePipeline pipeline = new FeaturePipeline(cfg, loader, invoker, (a, b, c) -> null);
        pipeline.run("auth", new FeaturePipeline.Options(true, false, null));

        assertEquals(List.of("create-tech-spec", "create-tasks"), invocations,
            "create-prd must be skipped — its output already exists");
    }

    @Test
    void forceFlagReRunsEverySkillEvenWhenOutputsExist() throws IOException {
        Path featuresDir = tempDir.resolve("features");
        SkillLoader loader = scaffoldSkillsRoot();
        Config cfg = configWithFeaturesAt(featuresDir);

        Path feat = Files.createDirectories(featuresDir.resolve("auth"));
        Files.writeString(feat.resolve("_prd.md"),      "stale");
        Files.writeString(feat.resolve("_techspec.md"), "stale");
        Files.writeString(feat.resolve("_tasks.md"),    "stale");

        List<String> invocations = new ArrayList<>();
        FeaturePipeline.SkillInvoker invoker = (name, feature, seed, model) -> {
            invocations.add(name);
            return ChatLoop.Outcome.COMPLETED;
        };

        new FeaturePipeline(cfg, loader, invoker, (a, b, c) -> null)
            .run("auth", new FeaturePipeline.Options(true, /*force*/ true, null));

        assertEquals(3, invocations.size(), "--force must re-run every skill");
    }

    @Test
    void abortedSkillStopsPipelineWithAbortedOutcome() throws IOException {
        Path featuresDir = tempDir.resolve("features");
        SkillLoader loader = scaffoldSkillsRoot();
        Config cfg = configWithFeaturesAt(featuresDir);

        FeaturePipeline.SkillInvoker invoker = (name, feature, seed, model) ->
            name.equals("create-tech-spec") ? ChatLoop.Outcome.ABORTED : ChatLoop.Outcome.COMPLETED;
        // first skill: pretend output landed
        Path feat = Files.createDirectories(featuresDir.resolve("auth"));
        Files.writeString(feat.resolve("_prd.md"), "done");

        FeaturePipeline.Outcome outcome = new FeaturePipeline(cfg, loader, invoker, (a, b, c) -> null)
            .run("auth", new FeaturePipeline.Options(true, true, null));

        assertEquals(FeaturePipeline.Outcome.ABORTED, outcome);
        assertFalse(Files.exists(feat.resolve("_techspec.md")));
    }

    @Test
    void gateStopAnswerHaltsPipelineAfterCurrentSkill() throws IOException {
        Path featuresDir = tempDir.resolve("features");
        SkillLoader loader = scaffoldSkillsRoot();
        Config cfg = configWithFeaturesAt(featuresDir);

        List<String> invocations = new ArrayList<>();
        FeaturePipeline.SkillInvoker invoker = (name, feature, seed, model) -> {
            invocations.add(name);
            Files.createDirectories(featuresDir.resolve(feature));
            Files.writeString(featuresDir.resolve(feature).resolve(
                name.equals("create-prd") ? "_prd.md" :
                name.equals("create-tech-spec") ? "_techspec.md" : "_tasks.md"),
                "ok");
            return ChatLoop.Outcome.COMPLETED;
        };
        FeaturePipeline.GateAsker stopAfterFirst = (a, b, c) -> FeaturePipeline.GateResponse.STOP;

        FeaturePipeline.Outcome outcome = new FeaturePipeline(cfg, loader, invoker, stopAfterFirst)
            .run("auth", new FeaturePipeline.Options(false, false, null));

        assertEquals(FeaturePipeline.Outcome.STOPPED, outcome);
        assertEquals(List.of("create-prd"), invocations);
    }

    @Test
    void seedFlagPropagatesOnlyToTheFirstSkill() throws IOException {
        Path featuresDir = tempDir.resolve("features");
        SkillLoader loader = scaffoldSkillsRoot();
        Config cfg = configWithFeaturesAt(featuresDir);
        Path seedPath = tempDir.resolve("notes.md");

        Map<String, Path> seedSeenBy = new LinkedHashMap<>();
        FeaturePipeline.SkillInvoker invoker = (name, feature, seed, model) -> {
            seedSeenBy.put(name, seed);
            Files.createDirectories(featuresDir.resolve(feature));
            Files.writeString(featuresDir.resolve(feature).resolve(
                name.equals("create-prd") ? "_prd.md" :
                name.equals("create-tech-spec") ? "_techspec.md" : "_tasks.md"),
                "ok");
            return ChatLoop.Outcome.COMPLETED;
        };

        new FeaturePipeline(cfg, loader, invoker, (a, b, c) -> null)
            .run("auth", new FeaturePipeline.Options(true, false, seedPath));

        assertEquals(seedPath, seedSeenBy.get("create-prd"));
        assertNull(seedSeenBy.get("create-tech-spec"));
        assertNull(seedSeenBy.get("create-tasks"));
    }
}
