package io.aicompanion;

import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import java.util.HashMap;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> overrides = new HashMap<>();
        boolean nonInteractive = false;
        boolean fresh         = false;
        boolean retryFailed   = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "run"                  -> nonInteractive = true;
                case "--features"           -> { if (i + 1 < args.length) overrides.put("features_dir", args[++i]); }
                case "--project"            -> { if (i + 1 < args.length) overrides.put("project_dir",  args[++i]); }
                case "--agent"              -> { if (i + 1 < args.length) overrides.put("agent",         args[++i]); }
                case "--test-command"       -> { if (i + 1 < args.length) overrides.put("test_command",  args[++i]); }
                case "--timeout"            -> { if (i + 1 < args.length) overrides.put("session_timeout_min", args[++i]); }
                case "--report-dir"         -> { if (i + 1 < args.length) overrides.put("report_dir",   args[++i]); }
                case "--no-tests"           -> overrides.put("test_enabled",    "false");
                case "--no-stop-on-failure" -> overrides.put("stop_on_failure", "false");
                case "--log-thoughts"       -> overrides.put("log_thoughts",    "true");
                case "--no-yolo"            -> overrides.put("yolo",            "false");
                case "--no-reports"         -> overrides.put("report_enabled",  "false");
                case "--fresh"              -> fresh        = true;
                case "--retry-failed"       -> retryFailed  = true;
                case "--version", "-v"      -> { System.out.println("aicompanion 1.0.0"); return; }
                case "--help", "-h"         -> { printUsage(); return; }
                default -> { /* ignore unknown flags */ }
            }
        }

        Config config;
        try {
            config = ConfigLoader.load(overrides);
        } catch (IllegalArgumentException e) {
            System.err.println("aicompanion: " + e.getMessage());
            System.exit(2);
            return;
        }

        if (nonInteractive) {
            new TaskRunner(config, new RunOptions(fresh, retryFailed)).run();
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
              --version | -v            Print version
              --help | -h               Print this help

            Environment variables:
              AICOMPANION_AGENT, AICOMPANION_TASKS_DIR, AICOMPANION_PROJECT_DIR, ...
              (AICOMPANION_<KEY> for any config key)

            Config file: .aicompanion.yml in the current directory
            See PLAN.md for full configuration reference.""");
    }
}
