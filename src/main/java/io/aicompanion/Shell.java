package io.aicompanion;

import io.aicompanion.agent.AgentRegistry;
import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import io.aicompanion.console.Ansi;
import io.aicompanion.skill.*;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.*;
import org.jline.terminal.Terminal.Signal;
import org.jline.terminal.Terminal.SignalHandler;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class Shell {

    private static final List<String> BUILT_IN_COMMANDS = List.of(
        "run", "tasks", "agents", "config", "status", "reset",
        "skills", "help", "?", "exit", "quit");

    private static final List<String> RUN_FLAGS = List.of(
        "--features", "--agent", "--model", "--project", "--test-command",
        "--no-tests", "--no-stop-on-failure", "--log-thoughts", "--no-yolo",
        "--fresh", "--retry-failed",
        "--pre-check-tests", "--task-preamble-strip", "--init-instructions",
        "--compact-after", "--fix-output-lines", "--max-tokens", "--dry-run-tokens");

    private static final List<String> SKILL_FLAGS = List.of("--seed", "--model");

    private static final List<String> CONFIG_KEYS = List.of(
        "agent", "model", "agent_extra_args", "features_dir",
        "task_extensions", "task_sort", "project_dir", "test_command",
        "test_enabled", "stop_on_failure", "max_fix_attempts", "session_timeout_min",
        "reuse_session", "report_dir", "report_enabled", "log_tool_calls",
        "log_thoughts", "yolo",
        "fix_output_max_lines", "task_preamble_strip", "compact_after_n_tasks",
        "pre_check_tests", "max_tokens_per_run", "init_instructions");

    private Config config;
    private List<SkillMetadata> skills = List.of();   // populated in start()

    public Shell(Config config) {
        this.config = config;
    }

    public void start() throws Exception {
        skills = discoverSkillsWithReporting();

        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .parser(new DefaultParser())
            .completer(buildCompleter())
            .variable(LineReader.HISTORY_FILE,
                System.getProperty("user.home") + "/.aicompanion_history")
            .variable(LineReader.HISTORY_SIZE,      5000)
            .variable(LineReader.HISTORY_FILE_SIZE, 5000)
            // Ctrl+R reverse-search is bound by default in the emacs keymap; these
            // options just keep the history clean across sessions.
            .option(LineReader.Option.HISTORY_IGNORE_DUPS,  true)
            .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
            .option(LineReader.Option.HISTORY_REDUCE_BLANKS, true)
            .option(LineReader.Option.HISTORY_INCREMENTAL,  true)
            .build();

        printBanner();
        String prompt = Ansi.bold(Ansi.cyan("aicompanion> "));

        while (true) {
            String line;
            try {
                line = reader.readLine(prompt).trim();
            } catch (UserInterruptException e) {
                // Ctrl+C at the prompt: don't exit — show a hint and re-prompt.
                System.out.println(Ansi.dim("(Use 'exit' or Ctrl+D to quit.)"));
                continue;
            } catch (EndOfFileException e) {
                System.out.println("Bye.");
                return;
            }
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "run"             -> handleRun(parts, terminal);
                case "tasks"           -> handleTasks();
                case "agents"          -> handleAgents();
                case "config"          -> handleConfig(parts);
                case "status"          -> handleStatus();
                case "reset"           -> handleReset(reader);
                case "skills"          -> handleSkills();
                case "help", "?"       -> printHelp();
                case "exit", "quit"    -> { System.out.println("Bye."); return; }
                default -> {
                    if (isDiscoveredSkill(parts[0])) {
                        handleSkillCommand(parts[0], parts, terminal);
                    } else {
                        System.out.println(Ansi.yellow(
                            "Unknown command: '" + parts[0] + "'. Type 'help'."));
                    }
                }
            }
        }
    }

    // ── skill discovery ──────────────────────────────────────────────────────

    private List<SkillMetadata> discoverSkillsWithReporting() {
        SkillLoader loader = new SkillLoader(SkillRunner.SKILLS_ROOT);
        List<SkillMetadata> found = new ArrayList<>();
        try {
            for (String name : loader.discover()) {
                try {
                    found.add(loader.describe(name));
                } catch (SkillLoadException e) {
                    System.err.println(Ansi.yellow(
                        "[skill] '" + name + "' skipped — " + e.getMessage()));
                }
            }
        } catch (IOException e) {
            System.err.println(Ansi.yellow(
                "[skill] could not scan " + SkillRunner.SKILLS_ROOT + ": " + e.getMessage()));
        }
        return List.copyOf(found);
    }

    private boolean isDiscoveredSkill(String name) {
        for (SkillMetadata md : skills) if (md.name().equals(name)) return true;
        return false;
    }

    // ── command handlers ─────────────────────────────────────────────────────

    private void handleRun(String[] parts, Terminal terminal) {
        FlagParser.ParseResult parsed = FlagParser.parse(parts, 1);
        Config effective = parsed.configOverrides().isEmpty()
            ? config
            : ConfigLoader.load(parsed.configOverrides());

        // Route Ctrl+C during the run to a thread interrupt so the runner can
        // bail out at the next task boundary. Restore the previous handler on
        // exit so the next readLine() behaves normally.
        Thread runner = Thread.currentThread();
        SignalHandler previous = terminal.handle(Signal.INT, sig -> runner.interrupt());
        try {
            new TaskRunner(effective, parsed.runOptions(), terminal).run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println(Ansi.yellow("\nAborted."));
        } catch (Exception e) {
            if (Thread.interrupted()) {
                System.out.println(Ansi.yellow("\nAborted."));
            } else {
                System.err.println(Ansi.red("Error: " + e.getMessage()));
            }
        } finally {
            terminal.handle(Signal.INT, previous);
            // Clear any lingering interrupt status so the next readLine works.
            Thread.interrupted();
        }
    }

    private void handleSkills() {
        if (skills.isEmpty()) {
            System.out.println(Ansi.yellow("No skills found at " + SkillRunner.SKILLS_ROOT + "."));
            System.out.println(Ansi.dim("Tip: run `init skills` to scaffold the canonical bundles."));
            return;
        }
        System.out.println("Skills in " + Ansi.cyan(SkillRunner.SKILLS_ROOT.toString()) + ":");
        for (SkillMetadata md : skills) {
            System.out.printf("  %s %-22s %s%n",
                Ansi.green("✓"), md.name(), Ansi.dim(md.description()));
            System.out.printf("    %s%n",
                Ansi.dim("output → <features_dir>/<feature>/" + md.outputRelativePath()));
        }
    }

    private void handleSkillCommand(String skillName, String[] parts, Terminal terminal) {
        SkillCommandArgs args = SkillCommandArgs.parse(parts, 1);
        if (args.feature() == null) {
            System.out.println(Ansi.yellow(
                "Usage: " + skillName + " <feature> [--seed <path>] [--model <name>]"));
            return;
        }
        runSkill(skillName, args, terminal);
    }

    /**
     * Spin up a chat-friendly LineReader (no shell completer so {@code you>}
     * doesn't try to autocomplete command names), wrap it as a
     * {@link UserInput}, route Ctrl+C to the agent thread, and invoke the
     * {@link SkillRunner}.
     */
    private void runSkill(String skillName, SkillCommandArgs args, Terminal terminal) {
        LineReader chatReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .variable(LineReader.HISTORY_FILE,
                System.getProperty("user.home") + "/.aicompanion_history")
            .variable(LineReader.HISTORY_SIZE,      5000)
            .variable(LineReader.HISTORY_FILE_SIZE, 5000)
            .option(LineReader.Option.HISTORY_IGNORE_DUPS,  true)
            .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
            .build();
        UserInput input = new JLineUserInput(chatReader);
        AgentConsole console = new AgentConsole(terminal);

        Thread runner = Thread.currentThread();
        SignalHandler previous = terminal.handle(Signal.INT, sig -> runner.interrupt());
        try {
            new SkillRunner(config, console, input).run(
                skillName, args.feature(), args.seed(), args.model());
        } catch (IOException e) {
            System.err.println(Ansi.red("I/O error: " + e.getMessage()));
        } catch (SkillLoadException e) {
            System.err.println(Ansi.red(e.getMessage()));
        } catch (RuntimeException e) {
            if (Thread.interrupted()) {
                System.out.println(Ansi.yellow("\nAborted."));
            } else {
                System.err.println(Ansi.red("Error: " + e.getMessage()));
            }
        } finally {
            terminal.handle(Signal.INT, previous);
            Thread.interrupted();
        }
    }

    private void handleTasks() {
        try {
            List<Batch> batches = new BatchResolver(config).resolveBatches();
            int total = batches.stream().mapToInt(b -> b.taskPaths().size()).sum();
            if (total == 0) {
                System.out.println(Ansi.yellow(
                    "No features with tasks found under: " + config.featuresDir()));
                return;
            }
            System.out.println("Features in " + Ansi.cyan(config.featuresDir())
                + " (" + batches.size() + " features, " + total + " tasks):");
            int n = 0;
            for (Batch batch : batches) {
                System.out.println("  " + Ansi.bold(batch.featureName()) + ":");
                for (Path f : batch.taskPaths()) {
                    System.out.printf("  %2d. %s%n", ++n, f.getFileName());
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(Ansi.red("Error listing tasks: " + e.getMessage()));
        }
    }

    private void handleStatus() {
        try {
            List<Batch> batches = new BatchResolver(config).resolveBatches();
            int total = batches.stream().mapToInt(b -> b.taskPaths().size()).sum();
            if (total == 0) {
                System.out.println(Ansi.yellow(
                    "No features with tasks found under: " + config.featuresDir()));
                return;
            }
            RunState state = RunState.load();
            System.out.println("Features in " + Ansi.cyan(config.featuresDir())
                + " (" + batches.size() + " features, " + total + " tasks):");
            int n = 0;
            for (Batch batch : batches) {
                System.out.println("  " + Ansi.bold(batch.featureName()) + ":");
                for (Path f : batch.taskPaths()) {
                    String key = batch.stateKeyFor(f);
                    String hash;
                    try { hash = RunState.hash(Files.readString(f)); }
                    catch (IOException e) { hash = ""; }
                    String label = state.labelFor(key, hash);
                    String marker = switch (label) {
                        case "passed" -> Ansi.green("✓");
                        case "failed" -> Ansi.red("✗");
                        case "edited" -> Ansi.yellow("~");
                        default       -> " ";
                    };
                    System.out.printf("  %s %2d. %-40s %s%n",
                        marker, ++n, f.getFileName(), Ansi.dim(label));
                }
            }
            System.out.println();
            System.out.printf("  %s passed   %s failed%n",
                Ansi.green(String.valueOf(state.passedCount())),
                Ansi.red(String.valueOf(state.failedCount())));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(Ansi.red("Error listing tasks: " + e.getMessage()));
        }
    }

    private void handleReset(LineReader reader) {
        Path stateFile = RunState.DEFAULT_PATH;
        if (!Files.exists(stateFile)) {
            System.out.println(Ansi.dim("No state file at " + stateFile + " — nothing to reset."));
            return;
        }
        String answer;
        try {
            answer = reader.readLine("Delete " + stateFile + "? [y/N] ").trim();
        } catch (UserInterruptException | EndOfFileException e) {
            System.out.println(Ansi.dim("cancelled"));
            return;
        }
        if (!answer.equalsIgnoreCase("y") && !answer.equalsIgnoreCase("yes")) {
            System.out.println(Ansi.dim("cancelled"));
            return;
        }
        try {
            RunState.delete();
            System.out.println(Ansi.green("✓ Cleared resume state."));
        } catch (IOException e) {
            System.err.println(Ansi.red("Could not delete state file: " + e.getMessage()));
        }
    }

    private void handleAgents() {
        var found = AgentRegistry.detectAll();
        if (found.isEmpty()) {
            System.out.println(Ansi.yellow("No AI agents detected on PATH."));
            System.out.println("Install one of: claude-code-acp, codex-acp, gemini, copilot, opencode");
        } else {
            System.out.println("Detected agents:");
            found.forEach(s -> System.out.printf("  %s %-14s %s%n",
                Ansi.green("✓"), s.id(), Ansi.dim("(" + s.executable() + ")")));
        }
    }

    private void handleConfig(String[] parts) {
        if (parts.length == 1) {
            printConfig();
        } else if (parts.length >= 4 && "set".equals(parts[1])) {
            String key = parts[2];
            String val = String.join(" ", Arrays.copyOfRange(parts, 3, parts.length));
            config = ConfigLoader.load(Map.of(key, val));
            System.out.println(Ansi.green("Set ") + key + " = " + val);
        } else {
            System.out.println("Usage: config  |  config set <key> <value>");
        }
    }

    private void printConfig() {
        System.out.println(Ansi.bold("Current configuration:"));
        row("agent",               config.agent()       != null ? config.agent()       : "(auto-detect)");
        row("model",               config.model()       != null ? config.model()       : "(agent default)");
        row("agent_extra_args",    config.agentExtraArgs().toString());
        row("features_dir",        config.featuresDir());
        row("task_extensions",     config.taskExtensions().toString());
        row("task_sort",           config.taskSort());
        row("project_dir",         config.projectDir());
        row("test_command",        config.testCommand()  != null ? config.testCommand() : "(auto-detect)");
        row("test_enabled",        String.valueOf(config.testEnabled()));
        row("stop_on_failure",     String.valueOf(config.stopOnFailure()));
        row("max_fix_attempts",    String.valueOf(config.maxFixAttempts()));
        row("session_timeout_min", String.valueOf(config.sessionTimeoutMin()));
        row("reuse_session",       String.valueOf(config.reuseSession()));
        row("report_dir",          config.reportDir());
        row("report_enabled",      String.valueOf(config.reportEnabled()));
        row("log_tool_calls",      String.valueOf(config.logToolCalls()));
        row("log_thoughts",        String.valueOf(config.logThoughts()));
        row("yolo",                String.valueOf(config.yolo()));
        row("fix_output_max_lines",   String.valueOf(config.fixOutputMaxLines()));
        row("task_preamble_strip",    String.valueOf(config.taskPreambleStrip()));
        row("compact_after_n_tasks",  String.valueOf(config.compactAfterNTasks()));
        row("pre_check_tests",        String.valueOf(config.preCheckTests()));
        row("max_tokens_per_run",     String.valueOf(config.maxTokensPerRun()));
        row("init_instructions",      String.valueOf(config.initInstructions()));
        if (!config.skills().isEmpty()) {
            row("skills (per-model)", "");
            for (Map.Entry<String, Config.SkillConfig> e : config.skills().entrySet()) {
                row("  " + e.getKey(), "model = " + e.getValue().model());
            }
        }
    }

    private void printBanner() {
        System.out.println(Ansi.bold("aicompanion v1.0.0"));
        System.out.println(Ansi.dim("agent: ")
            + (config.agent() != null ? config.agent() : "auto-detect")
            + Ansi.dim("  |  features: ") + config.featuresDir()
            + Ansi.dim("  |  skills: ") + skills.size());
        System.out.println(Ansi.dim("Type 'help' for available commands. Tab completes.\n"));
    }

    private void printHelp() {
        System.out.println(Help.render(skills));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void row(String key, String val) {
        System.out.printf("  %-22s = %s%n", key, val);
    }

    private Completer buildCompleter() {
        List<String> allCommands = new ArrayList<>(BUILT_IN_COMMANDS);
        for (SkillMetadata md : skills) allCommands.add(md.name());
        Completer topLevel = new StringsCompleter(allCommands);

        Completer runCompleter = new ArgumentCompleter(
            new StringsCompleter("run"),
            new StringsCompleter(RUN_FLAGS),
            NullCompleter.INSTANCE);

        Completer configSetCompleter = new ArgumentCompleter(
            new StringsCompleter("config"),
            new StringsCompleter("set"),
            new StringsCompleter(CONFIG_KEYS),
            NullCompleter.INSTANCE);

        // For each discovered skill: complete its flag names after the feature arg.
        List<Completer> skillCompleters = new ArrayList<>();
        for (SkillMetadata md : skills) {
            skillCompleters.add(new ArgumentCompleter(
                new StringsCompleter(md.name()),
                NullCompleter.INSTANCE,             // feature name — user-supplied
                new StringsCompleter(SKILL_FLAGS),
                NullCompleter.INSTANCE));
        }

        List<Completer> all = new ArrayList<>();
        all.add(topLevel);
        all.add(runCompleter);
        all.add(configSetCompleter);
        all.addAll(skillCompleters);
        return new AggregateCompleter(all.toArray(new Completer[0]));
    }
}
