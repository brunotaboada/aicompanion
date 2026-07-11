package io.aicompanion;

import io.aicompanion.skill.SkillMetadata;
import java.util.List;

public final class Help {

    private Help() {}

    /** CLI help — used by {@code aicompanion --help}. Focuses on one-shot invocations. */
    public static String cli(List<SkillMetadata> skills) {
        StringBuilder skillSection = new StringBuilder();
        if (!skills.isEmpty()) {
            skillSection.append("\n\nInteractive skill commands (each spawns an agent chat; needs a TTY):\n");
            for (SkillMetadata md : skills) {
                skillSection.append(String.format("  aicompanion %-22s %s%n",
                    md.name() + " <feature>", md.description()));
            }
            skillSection.append("  aicompanion create-feature <feature>     Run every skill above in order with review gates.\n");
            skillSection.append("\n  Per-skill flags (create-prd, create-tech-spec, create-tasks):\n");
            skillSection.append("    --seed <path>           pre-existing notes file to feed into the conversation\n");
            skillSection.append("    --model <name>          one-shot model override (beats skills.<name>.model)\n");
            skillSection.append("\n  create-feature flags:\n");
            skillSection.append("    --seed <path>           forwarded to the first step only\n");
            skillSection.append("    --auto                  skip the review gates between steps\n");
            skillSection.append("    --force                 ignore existing outputs and re-run every step\n");
        }
        return """
            aicompanion 1.0.0 — AI SDLC task runner

            Usage:
              aicompanion                          Open interactive shell (REPL)
              aicompanion run [options]            Execute all tasks non-interactively
              aicompanion <skill> <feature> [..]   Run one skill interactively (create-prd, …)
              aicompanion create-feature <feat>    Run create-prd → tech-spec → tasks
              aicompanion init skills [--force]    Scaffold .agents/skills/ from bundled defaults
              aicompanion --help | -h              Print this help
              aicompanion --version | -v           Print version

            Options (for `run`):
              --features <dir>          Features parent dir (default: features).
                                        Each subdir must contain a tasks/ folder; the
                                        runner iterates features alphabetically and
                                        ships each feature's tasks in order.
              --project <dir>           Project root for agent session (default: .)
              --agent <id>              Agent: claude, codex, gemini, copilot, opencode
              --model <name>            Pin a model for the run
              --test-command <cmd>      Override test command
              --verify-commands <list>  Comma-separated commands run in order
                                        (first failure feeds the fix loop; overrides test command)
              --timeout <minutes>       ACP session timeout (default: 10)
              --test-timeout <minutes>  Kill the test command after N minutes (default: 30; 0 = no limit)
              --report-dir <dir>        Report output directory
              --no-tests                Skip test verification
              --no-stop-on-failure      Continue even if tests fail
              --log-thoughts            Print agent reasoning to console
              --no-yolo                 Do not pass --yolo to the agent
              --no-reports              Do not write markdown logs
              --fresh                   Clear .aicompanion/state.yml; run all tasks
              --retry-failed            Resume but re-run previously failed tasks

            Token-saving (for `run`):
              --pre-check-tests         Run tests before each task; skip agent if green
              --task-preamble-strip     Drop content before the first Markdown heading
              --init-instructions       Send summary-format rules once per session
              --compact-after <N>       Recycle ACP session every N tasks
              --fix-output-lines <N>    Cap retry test output (default: 200; 0 = unbounded)
              --max-tokens <N>          Stop the run once estimated tokens cross N
              --dry-run-tokens          Estimate prompt tokens without invoking the agent

            Configuration precedence (low → high):
              AICOMPANION_<KEY> env var  →  .aicompanion.yml  →  --flag

            For commands inside the REPL, run `aicompanion` then type `help`.
            See README.md for full configuration reference.""" + skillSection;
    }

    /** REPL help — used by {@code help} / {@code ?} inside the shell. */
    public static String repl(List<SkillMetadata> skills) {
        StringBuilder skillSection = new StringBuilder();
        if (!skills.isEmpty()) {
            skillSection.append("\n\nSkill commands (each runs independently as its own chat):\n");
            for (SkillMetadata md : skills) {
                skillSection.append(String.format("  %-28s %s%n",
                    md.name() + " <feature>", md.description()));
            }
            skillSection.append("\nPipeline command (runs all three skills above with review gates):\n");
            skillSection.append("  create-feature <feature>     auto-chain create-prd → create-tech-spec → create-tasks\n");
            skillSection.append("\n  Per-skill flags (create-prd, create-tech-spec, create-tasks):\n");
            skillSection.append("    --seed <path>              pre-existing notes file to feed in\n");
            skillSection.append("    --model <name>             one-shot model override (beats skills.<name>.model)\n");
            skillSection.append("\n  create-feature flags:\n");
            skillSection.append("    --seed <path>              forwarded to the first step only\n");
            skillSection.append("    --auto                     skip the review gates between steps\n");
            skillSection.append("    --force                    ignore existing outputs and re-run every step\n");
            skillSection.append("\n  Chat sentinels (typed at the `you>` prompt):\n");
            skillSection.append("    /abort                     end the session immediately, no files written\n");
            skillSection.append("    /done                      ask the agent to wrap up with what it has\n");
            skillSection.append("    /skip                      relay as \"no preference, pick a default\"\n");
            skillSection.append("    /edit                      open $EDITOR for a multi-line answer\n");
            skillSection.append("\n  Per-skill model in .aicompanion.yml:\n");
            skillSection.append("    skills:\n");
            skillSection.append("      create-prd:        { model: opus }\n");
            skillSection.append("      create-tech-spec:  { model: opus }\n");
            skillSection.append("      create-tasks:      { model: sonnet }\n");
        }
        return """
            REPL commands (type at the `aicompanion>` prompt):

            Task execution:
              run [flags]                  Execute all tasks (Tab to see flags)
              tasks                        List features and their tasks
              status                       Show pass/fail per task across runs
              reset                        Clear resume state (.aicompanion/state.yml)

            Environment:
              agents                       Detected AI agents on PATH
              config                       Show current configuration
              config set <key> <value>     Update a setting at runtime
              skills                       List discovered skills and validate each SKILL.md
              init skills [--force]        Scaffold .agents/skills/ from bundled defaults

            Help / quit:
              help | ?                     Show this help
              exit | quit                  Exit the shell  (Ctrl+D also works)

            Run flags (Tab in the REPL completes them):
              --features <dir>             --project <dir>            --agent <id>
              --model <name>               --test-command <cmd>       --verify-commands <list>
              --timeout <min>              --test-timeout <min>       --no-tests
              --no-stop-on-failure         --log-thoughts             --no-yolo
              --fresh                      --retry-failed             --pre-check-tests
              --task-preamble-strip        --init-instructions        --compact-after <N>
              --fix-output-lines <N>       --max-tokens <N>

            REPL keys:  Tab=complete  Ctrl+R=history  Ctrl+C=abort  Ctrl+D=quit""" + skillSection;
    }
}
