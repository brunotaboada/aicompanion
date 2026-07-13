package io.aicompanion;

import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import io.aicompanion.skill.*;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if ("--version".equals(arg) || "-v".equals(arg)) { System.out.println("aicompanion 1.0.0"); return; }
            if ("--help".equals(arg)    || "-h".equals(arg)) {
                System.out.println(Help.cli(discoverSkillsQuietly()));
                return;
            }
        }

        // aicompanion init skills [--force]
        if (args.length >= 2 && "init".equals(args[0]) && "skills".equals(args[1])) {
            boolean force = args.length > 2 && "--force".equals(args[2]);
            runInitSkills(force);
            return;
        }

        // aicompanion create-feature <feature> [--auto] [--force] [--seed <path>]
        if (args.length > 0 && "create-feature".equals(args[0])) {
            runCreateFeatureFromCli(args);
            return;
        }

        // Skill commands have the form: aicompanion <skill-name> <feature> [--seed ...] [--model ...]
        if (args.length > 0 && isSkillName(args[0])) {
            runSkillFromCli(args);
            return;
        }

        FlagParser.ParseResult parsed = FlagParser.parse(args, 0);

        Config config;
        try {
            config = ConfigLoader.load(parsed.configOverrides());
        } catch (IllegalArgumentException e) {
            System.err.println("aicompanion: " + msg(e));
            System.exit(2);
            return;
        }

        if (parsed.nonInteractive()) {
            try {
                RunResult result = new TaskRunner(config, parsed.runOptions()).run();
                System.exit(result.exitCode());
            } catch (IllegalStateException e) {
                System.err.println("aicompanion: " + msg(e));
                System.exit(1);
            } catch (IOException e) {
                System.err.println("aicompanion: I/O error — " + msg(e));
                System.exit(1);
            }
        } else {
            new Shell(config, parsed.runOptions()).start();
        }
    }

    private static void runInitSkills(boolean force) {
        try {
            SkillScaffolder.Result r = new SkillScaffolder().scaffold(Path.of("."), force);
            System.out.println("Scaffolded into .agents/skills/");
            for (String name : r.created()) System.out.println("  ✓ created  " + name);
            for (String name : r.skipped()) System.out.println("  ∙ skipped  " + name
                + "  (already exists — use --force to overwrite)");
            if (r.isEmpty()) {
                System.err.println("aicompanion: no skills found on the classpath.");
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("aicompanion: init failed — " + e.getMessage());
            System.exit(1);
        }
    }

    private static boolean isSkillName(String name) {
        for (SkillMetadata md : discoverSkillsQuietly()) {
            if (md.name().equals(name)) return true;
        }
        return false;
    }

    private static List<SkillMetadata> discoverSkillsQuietly() {
        return discoverSkillsQuietly(Path.of(".").resolve(SkillRunner.SKILLS_ROOT));
    }

    private static List<SkillMetadata> discoverSkillsQuietly(Path skillsRoot) {
        SkillLoader loader = new SkillLoader(skillsRoot);
        List<String> names;
        try {
            names = loader.discover();
        } catch (IOException e) {
            return List.of();
        }
        return names.stream()
            .map(n -> {
                try { return loader.describe(n); }
                catch (IOException | SkillLoadException e) { return null; }
            })
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    /**
     * Drive the full pipeline from {@code aicompanion create-feature <feature> ...}.
     * Mirrors the REPL handler but with a fresh JLine terminal since there's no
     * surrounding shell to borrow from.
     */
    private static void runCreateFeatureFromCli(String[] args) throws Exception {
        String  feature = null;
        Path    seed    = null;
        boolean auto    = false;
        boolean force   = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--seed"  -> { if (i + 1 < args.length) seed = Path.of(args[++i]); }
                case "--auto"  -> auto  = true;
                case "--force" -> force = true;
                default -> { if (feature == null && !args[i].startsWith("--")) feature = args[i]; }
            }
        }
        if (feature == null) {
            System.err.println(
                "Usage: aicompanion create-feature <feature> [--seed <path>] [--auto] [--force]");
            System.exit(2);
            return;
        }

        Config config;
        try {
            config = ConfigLoader.load(java.util.Map.of());
        } catch (IllegalArgumentException e) {
            System.err.println("aicompanion: " + msg(e));
            System.exit(2);
            return;
        }

        Terminal terminal = buildTerminal();
        UserInput chatInput = new JLineUserInput(LineReaderBuilder.builder().terminal(terminal).build());
        AgentConsole console = new AgentConsole(terminal);
        FeaturePipeline.GateAsker gate = new JLineGateAsker(LineReaderBuilder.builder().terminal(terminal).build());

        SkillLoader loader = new SkillLoader(SkillRunner.skillsRoot(config));
        SkillRunner runner = new SkillRunner(config, console, chatInput, loader);
        FeaturePipeline.SkillInvoker invoker = runner::run;
        FeaturePipeline pipeline = new FeaturePipeline(config, loader, invoker, gate);

        try {
            pipeline.run(feature, new FeaturePipeline.Options(auto, force, seed));
        } catch (SkillLoadException e) {
            System.err.println("aicompanion: " + msg(e));
            System.exit(2);
        } catch (IOException e) {
            System.err.println("aicompanion: I/O error — " + msg(e));
            System.exit(1);
        }
    }

    /**
     * Drive a skill from {@code aicompanion <skill> <feature> [...]}. Builds a
     * dedicated JLine terminal + reader since there is no surrounding shell to
     * share with.
     */
    private static void runSkillFromCli(String[] args) throws Exception {
        String skillName = args[0];
        SkillCommandArgs parsed = SkillCommandArgs.parse(args, 1);
        if (parsed.feature() == null) {
            System.err.println("Usage: aicompanion " + skillName
                + " <feature> [--seed <path>] [--model <name>]");
            System.exit(2);
            return;
        }

        Config config;
        try {
            config = ConfigLoader.load(java.util.Map.of());
        } catch (IllegalArgumentException e) {
            System.err.println("aicompanion: " + msg(e));
            System.exit(2);
            return;
        }

        Terminal terminal = buildTerminal();
        UserInput input = new JLineUserInput(LineReaderBuilder.builder().terminal(terminal).build());
        AgentConsole console = new AgentConsole(terminal);

        try {
            new SkillRunner(config, console, input).run(
                skillName, parsed.feature(), parsed.seed(), parsed.model());
        } catch (SkillLoadException e) {
            System.err.println("aicompanion: " + msg(e));
            System.exit(2);
        } catch (IOException e) {
            System.err.println("aicompanion: I/O error — " + msg(e));
            System.exit(1);
        }
    }

    private static Terminal buildTerminal() throws IOException {
        return TerminalBuilder.builder().system(true).build();
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        return m != null ? m : e.getClass().getSimpleName();
    }
}
