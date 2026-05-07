package io.aicompanion;

import io.aicompanion.agent.AgentRegistry;
import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class Shell {

    private Config config;

    public Shell(Config config) {
        this.config = config;
    }

    public void start() throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .parser(new DefaultParser())
            .variable(LineReader.HISTORY_FILE,
                System.getProperty("user.home") + "/.aicompanion_history")
            .build();

        printBanner();

        while (true) {
            String line;
            try {
                line = reader.readLine("aicompanion> ").trim();
            } catch (EndOfFileException | UserInterruptException e) {
                System.out.println("\nBye.");
                return;
            }
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "run"             -> handleRun(parts);
                case "tasks"           -> handleTasks();
                case "agents"          -> handleAgents();
                case "config"          -> handleConfig(parts);
                case "help", "?"       -> printHelp();
                case "exit", "quit"    -> { System.out.println("Bye."); return; }
                default -> System.out.println(
                    "Unknown command: '" + parts[0] + "'. Type 'help'.");
            }
        }
    }

    // ── command handlers ─────────────────────────────────────────────────────

    private void handleRun(String[] parts) {
        Map<String, String> overrides = parseFlags(parts, 1);
        Config effective = overrides.isEmpty() ? config : ConfigLoader.load(overrides);
        try {
            new TaskRunner(effective).run();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private void handleTasks() {
        Path dir = Path.of(config.tasksDir());
        if (!Files.isDirectory(dir)) {
            System.out.println("Tasks directory not found: " + dir.toAbsolutePath());
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
                System.out.println("No task files found in: " + dir.toAbsolutePath());
                return;
            }
            System.out.println("Tasks in " + config.tasksDir() + " (" + files.size() + "):");
            for (int i = 0; i < files.size(); i++) {
                System.out.printf("  %2d. %s%n", i + 1, files.get(i).getFileName());
            }
        } catch (IOException e) {
            System.err.println("Error listing tasks: " + e.getMessage());
        }
    }

    private void handleAgents() {
        var found = AgentRegistry.detectAll();
        if (found.isEmpty()) {
            System.out.println("No AI agents detected on PATH.");
            System.out.println("Install one of: claude-code-acp, codex-acp, gemini, copilot, opencode");
        } else {
            System.out.println("Detected agents:");
            found.forEach(s -> System.out.printf("  ✓ %-14s (%s)%n", s.id(), s.executable()));
        }
    }

    private void handleConfig(String[] parts) {
        if (parts.length == 1) {
            printConfig();
        } else if (parts.length >= 4 && "set".equals(parts[1])) {
            String key = parts[2];
            String val = String.join(" ", Arrays.copyOfRange(parts, 3, parts.length));
            config = ConfigLoader.load(Map.of(key, val));
            System.out.println("Set " + key + " = " + val);
        } else {
            System.out.println("Usage: config  |  config set <key> <value>");
        }
    }

    private void printConfig() {
        System.out.println("Current configuration:");
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
        System.out.println("aicompanion v1.0.0");
        System.out.println("agent: " + (config.agent() != null ? config.agent() : "auto-detect")
            + "  |  tasks: " + config.tasksDir());
        System.out.println("Type 'help' for available commands.\n");
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

            Configurable keys:
              agent, model, agent_extra_args, tasks_dir, task_extensions, task_sort,
              project_dir, test_command, test_enabled, stop_on_failure,
              max_fix_attempts, session_timeout_min, report_dir, report_enabled,
              log_tool_calls, log_thoughts, yolo

            Environment overrides: AICOMPANION_<KEY> (e.g. AICOMPANION_AGENT=gemini)
            Config file: .aicompanion.yml in working directory""");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void row(String key, String val) {
        System.out.printf("  %-22s = %s%n", key, val);
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
