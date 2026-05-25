package io.aicompanion;

public final class Help {

    private Help() {}

    public static final String TEXT = """
        aicompanion 1.0.0 — AI SDLC task runner

        Usage:
          aicompanion                          Interactive shell (REPL)
          aicompanion run [options]            Run all tasks non-interactively
          aicompanion --help | -h              Print this help
          aicompanion --version | -v           Print version

        Interactive shell commands:
          run [flags]               Execute all tasks (Tab to see flags)
          tasks | status            List task files / show pass/fail per task
          agents | config           Detected agents / current configuration
          config set <key> <val>    Update a setting at runtime
          reset                     Clear resume state (.aicompanion/state.yml)
          help | ?                  Show this help
          exit | quit               Quit shell

        Options (for `run`, CLI or REPL):
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

        REPL keys:  Tab=complete  Ctrl+R=history  Ctrl+C=abort run  Ctrl+D=quit

        Resume:  passed/failed tasks auto-skip on next `run`.
                 --fresh wipes state; --retry-failed reruns only failures.

        Configuration precedence (low → high):
          AICOMPANION_<KEY> env var  →  .aicompanion.yml  →  --flag

        See README.md for full configuration reference.""";
}
