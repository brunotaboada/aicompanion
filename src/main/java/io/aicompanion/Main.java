package io.aicompanion;

import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import io.aicompanion.skill.*;
import org.jline.reader.LineReader;
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
                System.out.println(Help.render(discoverSkillsQuietly()));
                return;
            }
        }

        // aicompanion init skills [--force]
        if (args.length >= 2 && "init".equals(args[0]) && "skills".equals(args[1])) {
            boolean force = args.length > 2 && "--force".equals(args[2]);
            runInitSkills(force);
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
            System.err.println("aicompanion: " + e.getMessage());
            System.exit(2);
            return;
        }

        if (parsed.nonInteractive()) {
            new TaskRunner(config, parsed.runOptions()).run();
        } else {
            new Shell(config).start();
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
        SkillLoader loader = new SkillLoader(SkillRunner.SKILLS_ROOT);
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
            System.err.println("aicompanion: " + e.getMessage());
            System.exit(2);
            return;
        }

        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        UserInput input = new JLineUserInput(reader);
        AgentConsole console = new AgentConsole(terminal);

        try {
            new SkillRunner(config, console, input).run(
                skillName, parsed.feature(), parsed.seed(), parsed.model());
        } catch (SkillLoadException e) {
            System.err.println("aicompanion: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("aicompanion: I/O error — " + e.getMessage());
            System.exit(1);
        }
    }
}
