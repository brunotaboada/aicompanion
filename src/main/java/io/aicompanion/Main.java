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
