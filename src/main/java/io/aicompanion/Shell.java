package io.aicompanion;

import io.aicompanion.agent.AgentRegistry;
import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import io.aicompanion.console.Ansi;
import io.aicompanion.console.StatusBar;
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

    private static final List<String> COMMANDS = List.of(
        "run", "tasks", "agents", "config", "help", "?", "exit", "quit");

    private static final List<String> RUN_FLAGS = List.of(
        "--tasks", "--agent", "--model", "--project", "--test-command",
        "--no-tests", "--no-stop-on-failure", "--log-thoughts", "--no-yolo");

    private static final List<String> CONFIG_KEYS = List.of(
        "agent", "model", "agent_extra_args", "tasks_dir", "task_extensions",
        "task_sort", "project_dir", "test_command", "test_enabled",
        "stop_on_failure", "max_fix_attempts", "session_timeout_min",
        "reuse_session", "report_dir", "report_enabled", "log_tool_calls",
        "log_thoughts", "yolo");

    private Config config;

    public Shell(Config config) {
        this.config = config;
    }

    public void start() throws Exception {
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
                case "help", "?"       -> printHelp();
                case "exit", "quit"    -> { System.out.println("Bye."); return; }
                default -> System.out.println(Ansi.yellow(
                    "Unknown command: '" + parts[0] + "'. Type 'help'."));
            }
        }
    }

    // ── command handlers ─────────────────────────────────────────────────────

    private void handleRun(String[] parts, Terminal terminal) {
        Map<String, String> overrides = parseFlags(parts, 1);
        Config effective = overrides.isEmpty() ? config : ConfigLoader.load(overrides);

        // Pin a status bar at the bottom of the screen for the duration of
        // the run, then route Ctrl+C to a thread interrupt so the runner can
        // bail out at the next task boundary. Both are torn down in finally
        // so the next readLine() behaves normally.
        StatusBar bar = StatusBar.attach(terminal);
        Thread runner = Thread.currentThread();
        SignalHandler previous = terminal.handle(Signal.INT, sig -> runner.interrupt());
        try {
            new TaskRunner(effective, bar).run();
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
            bar.close();
            // Clear any lingering interrupt status so the next readLine works.
            Thread.interrupted();
        }
    }

    private void handleTasks() {
        Path dir = Path.of(config.tasksDir());
        if (!Files.isDirectory(dir)) {
            System.out.println(Ansi.yellow(
                "Tasks directory not found: " + dir.toAbsolutePath()));
            return;
        }
        try (var stream = Files.list(dir)) {
            List<Path> files = stream
                .filter(p -> {
                    String name = p.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    String ext = dot >= 0 ? name.substring(dot + 1) : "";
                    return config.taskExtensions().contains(ext);
                })
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();

            if (files.isEmpty()) {
                System.out.println(Ansi.yellow(
                    "No task files found in: " + dir.toAbsolutePath()));
                return;
            }
            System.out.println("Tasks in " + Ansi.cyan(config.tasksDir())
                + " (" + files.size() + "):");
            for (int i = 0; i < files.size(); i++) {
                System.out.printf("  %2d. %s%n", i + 1, files.get(i).getFileName());
            }
        } catch (IOException e) {
            System.err.println(Ansi.red("Error listing tasks: " + e.getMessage()));
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
        row("tasks_dir",           config.tasksDir());
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
    }

    private void printBanner() {
        System.out.println(Ansi.bold("aicompanion v1.0.0"));
        System.out.println(Ansi.dim("agent: ")
            + (config.agent() != null ? config.agent() : "auto-detect")
            + Ansi.dim("  |  tasks: ") + config.tasksDir());
        System.out.println(Ansi.dim("Type 'help' for available commands. Tab completes.\n"));
    }

    private void printHelp() {
        System.out.println("""
            Commands:
              run [--tasks <dir>] [--agent <id>] [--model <id>] [--project <dir>]
                  [--no-tests] [--no-stop-on-failure] [--log-thoughts]
                                      Execute all tasks through the agent
              tasks                   List task files in the configured directory
              agents                  List installed AI agents
              config                  Show current configuration
              config set <key> <val>  Update a setting at runtime
              help | ?                Show this help
              exit | quit             Exit

            Tips:
              Tab           Complete commands, flags, and config keys
              Ctrl+R        Reverse-search command history
              Ctrl+C        Abort an in-flight `run` (or dismiss prompt input)
              Ctrl+D        Quit

            Configurable keys:
              agent, model, agent_extra_args, tasks_dir, task_extensions, task_sort,
              project_dir, test_command, test_enabled, stop_on_failure,
              max_fix_attempts, session_timeout_min, report_dir, report_enabled,
              log_tool_calls, log_thoughts, yolo

            Environment overrides: AICOMPANION_<KEY> (e.g. AICOMPANION_AGENT=gemini)
            Config file: .aicompanion.yml in working directory

            test_command:
              By default, the value is whitespace-split and exec'd directly — no shell.
              Prefix with `shell:` to run through PowerShell (Windows) or /bin/sh (Unix),
              enabling pipes, &&, redirects, env expansion, etc.
              Example:  shell:mvn test -q | grep -v WARNING""");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void row(String key, String val) {
        System.out.printf("  %-22s = %s%n", key, val);
    }

    private Completer buildCompleter() {
        Completer topLevel = new StringsCompleter(COMMANDS);

        Completer runCompleter = new ArgumentCompleter(
            new StringsCompleter("run"),
            new StringsCompleter(RUN_FLAGS),
            NullCompleter.INSTANCE);

        Completer configSetCompleter = new ArgumentCompleter(
            new StringsCompleter("config"),
            new StringsCompleter("set"),
            new StringsCompleter(CONFIG_KEYS),
            NullCompleter.INSTANCE);

        return new AggregateCompleter(topLevel, runCompleter, configSetCompleter);
    }

    /** Parse --key value pairs from a split command line starting at index `from`. */
    private static Map<String, String> parseFlags(String[] parts, int from) {
        Map<String, String> result = new HashMap<>();
        for (int i = from; i < parts.length - 1; i++) {
            String p = parts[i];
            if (p.startsWith("--")) {
                String key = p.substring(2).replace('-', '_');
                switch (key) {
                    case "no_tests"           -> result.put("test_enabled",    "false");
                    case "no_stop_on_failure" -> result.put("stop_on_failure", "false");
                    case "log_thoughts"       -> result.put("log_thoughts",    "true");
                    case "no_yolo"            -> result.put("yolo",            "false");
                    default -> { result.put(key, parts[i + 1]); i++; }
                }
            }
        }
        return result;
    }
}
