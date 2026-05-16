package io.aicompanion;

import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;

public class Main {

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if ("--version".equals(arg) || "-v".equals(arg)) { System.out.println("aicompanion 1.0.0"); return; }
            if ("--help".equals(arg)    || "-h".equals(arg)) { printUsage(); return; }
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

    private static void printUsage() {
        System.out.println("""
            aicompanion 1.0.0 — AI SDLC task runner

            Usage:
              aicompanion                          Interactive shell (REPL)
              aicompanion run [options]            Run all tasks non-interactively

            Options:
              --features <dir>          Features parent dir (default: features).
                                        Each subdir must contain a tasks/ folder; the
                                        runner iterates features alphabetically and
                                        ships each feature's tasks in order.
              --project <dir>           Project root for agent session (default: .)
              --agent <id>              Agent: claude, codex, gemini, copilot, opencode
              --test-command <cmd>      Override test command
              --timeout <minutes>       ACP session timeout (default: 10)
              --report-dir <dir>        Report output directory
              --no-tests                Skip test verification
              --no-stop-on-failure      Continue even if tests fail
              --log-thoughts            Print agent reasoning to console
              --no-yolo                 Do not pass --yolo to the agent
              --no-reports              Do not write markdown logs
              --fresh                   Clear .aicompanion/state.yml; run all tasks
              --retry-failed            Resume but re-run previously failed tasks

            Token-saving:
              --pre-check-tests         Run tests before each task; skip agent if green
              --task-preamble-strip     Drop content before the first Markdown heading
              --init-instructions       Send summary-format rules once per session
              --compact-after <N>       Recycle ACP session every N tasks
              --fix-output-lines <N>    Cap retry test output (default: 200; 0 = unbounded)
              --max-tokens <N>          Stop the run once estimated tokens cross N
              --dry-run-tokens          Estimate prompt tokens without invoking the agent

              --version | -v            Print version
              --help | -h               Print this help

            Environment variables:
              AICOMPANION_AGENT, AICOMPANION_TASKS_DIR, AICOMPANION_PROJECT_DIR, ...
              (AICOMPANION_<KEY> for any config key)

            Config file: .aicompanion.yml in the current directory
            See PLAN.md for full configuration reference.""");
    }
}
