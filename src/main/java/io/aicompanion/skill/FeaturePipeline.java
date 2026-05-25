package io.aicompanion.skill;

import io.aicompanion.config.Config;
import io.aicompanion.console.Ansi;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * Drive every discovered skill in order with a review gate between each one.
 * Resumable: if a skill's output already exists, the step is skipped (unless
 * {@code --force}), so re-running {@code create-feature auth-system} after a
 * mid-pipeline {@code /abort} picks up where you left off.
 *
 * <p>Hard rule: gates run between steps, never inside them. The chat loop
 * inside each skill stays uncluttered by pipeline concerns.
 */
public final class FeaturePipeline {

    /** Skills to run in order. Pinned to keep the pipeline deterministic. */
    public static final List<String> SKILLS_IN_ORDER = List.of(
        "create-prd", "create-tech-spec", "create-tasks");

    /** Outcome categories. */
    public enum Outcome { ALL_COMPLETED, STOPPED, ABORTED }

    /** User's answer at a gate prompt between two pipeline steps. */
    public enum GateResponse {
        CONTINUE,         // [Y] — proceed to the next step
        EDIT_AND_CONTINUE, // [e] — open $EDITOR on the just-produced file, then continue
        STOP              // [n] — halt the pipeline
    }

    /** Per-step inputs. {@code auto} skips gates, {@code force} ignores existing outputs. */
    public record Options(boolean auto, boolean force, Path seed) {
        public static Options defaults() { return new Options(false, false, null); }
    }

    /** Functional handle to run one skill — production passes {@link SkillRunner#run}. */
    @FunctionalInterface
    public interface SkillInvoker {
        ChatLoop.Outcome invoke(String skillName, String featureName,
                                Path seed, String modelOverride) throws IOException;
    }

    /** Asks the user whether to continue between steps. */
    @FunctionalInterface
    public interface GateAsker {
        GateResponse ask(String justCompleted, String next, Path completedOutput);
    }

    private final Config        config;
    private final SkillLoader   loader;
    private final SkillInvoker  invoker;
    private final GateAsker     gateAsker;

    public FeaturePipeline(Config config, SkillLoader loader,
                           SkillInvoker invoker, GateAsker gateAsker) {
        this.config    = config;
        this.loader    = loader;
        this.invoker   = invoker;
        this.gateAsker = gateAsker;
    }

    /**
     * Run every step. Returns a high-level outcome; per-step status is
     * announced inline as the pipeline progresses.
     */
    public Outcome run(String featureName, Options opts) throws IOException {
        Path featureDir = Path.of(config.featuresDir()).resolve(featureName);
        Files.createDirectories(featureDir);

        for (int i = 0; i < SKILLS_IN_ORDER.size(); i++) {
            String skill = SKILLS_IN_ORDER.get(i);
            int    n     = i + 1;
            int    total = SKILLS_IN_ORDER.size();

            Path outputFile;
            try {
                SkillMetadata md = loader.describe(skill);
                outputFile = featureDir.resolve(md.outputRelativePath());
            } catch (SkillLoadException e) {
                System.err.println(Ansi.red("[" + n + "/" + total + "] " + skill
                    + " — skill not available: " + e.getMessage()));
                return Outcome.STOPPED;
            }

            boolean alreadyComplete = Files.exists(outputFile);
            if (alreadyComplete && !opts.force()) {
                System.out.println(Ansi.green(
                    "[" + n + "/" + total + "] " + skill + " — skipped (output exists: "
                        + outputFile + ")"));
                if (i < SKILLS_IN_ORDER.size() - 1 && !opts.auto()) {
                    GateResponse gate = gateAsker.ask(skill, SKILLS_IN_ORDER.get(i + 1), outputFile);
                    if (gate == GateResponse.STOP) return Outcome.STOPPED;
                    if (gate == GateResponse.EDIT_AND_CONTINUE) openEditor(outputFile);
                }
                continue;
            }

            System.out.println(Ansi.bold(Ansi.cyan(
                "[" + n + "/" + total + "] " + skill + " " + featureName)));

            // --seed only flows into the first executed step; subsequent steps
            // already have richer prior-step output to ground in.
            Path seedThisStep = (i == 0) ? opts.seed() : null;

            ChatLoop.Outcome outcome = invoker.invoke(skill, featureName, seedThisStep, null);

            switch (outcome) {
                case COMPLETED -> { /* fall through to gate */ }
                case ABORTED -> {
                    System.out.println(Ansi.yellow(
                        "Pipeline stopped — " + skill + " was aborted."));
                    return Outcome.ABORTED;
                }
                case DONE_FORCED -> {
                    if (!Files.exists(outputFile)) {
                        System.out.println(Ansi.yellow(
                            "Pipeline stopped — " + skill + " ended via /done with no output."));
                        return Outcome.STOPPED;
                    }
                }
            }

            // Gate after every step except the last
            if (i < SKILLS_IN_ORDER.size() - 1 && !opts.auto()) {
                GateResponse gate = gateAsker.ask(skill, SKILLS_IN_ORDER.get(i + 1), outputFile);
                switch (gate) {
                    case CONTINUE -> { /* loop */ }
                    case EDIT_AND_CONTINUE -> openEditor(outputFile);
                    case STOP -> {
                        System.out.println(Ansi.yellow(
                            "Pipeline stopped at " + skill + ". Resume by re-running `create-feature " + featureName + "`."));
                        return Outcome.STOPPED;
                    }
                }
            }
        }

        System.out.println(Ansi.green("✓ Feature '" + featureName + "' ready. "
            + "Run with: run --features " + Path.of(config.featuresDir()).resolve(featureName)));
        return Outcome.ALL_COMPLETED;
    }

    private void openEditor(Path file) {
        try {
            String editorCmd = System.getenv("VISUAL");
            if (editorCmd == null || editorCmd.isBlank()) editorCmd = System.getenv("EDITOR");
            if (editorCmd == null || editorCmd.isBlank()) editorCmd = "vi";
            new ProcessBuilder(editorCmd, file.toString()).inheritIO().start().waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println(Ansi.yellow("[edit] " + e.getMessage() + " — continuing without edit."));
        }
    }
}
