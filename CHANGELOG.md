# Changelog

## [Unreleased]

### Added
- **`verify_commands`** — a list of verification commands (lint, typecheck, test, …) run in order after each task; the first non-zero exit stops the sequence and its output plus the failing command's name feed the fix loop. Overrides `test_command` when set; each entry supports the same `shell:` prefix and quoting rules. Available as `verify_commands:` in `.aicompanion.yml`, `--verify-commands "a,b"` on the CLI, and `AICOMPANION_VERIFY_COMMANDS` in the environment
- **Live status bar** — REPL `run` pins a bottom-row status bar (agent / model / task / tests / fix state) using a DECSTBM scroll region so streamed agent output never collides with it; no-op on dumb/no-TTY terminals. Ported from the `claude/console-ux-improvements-kTU2m` branch onto the current architecture (that branch was cut from a pre-skills snapshot and should be closed unmerged)
- **Test command timeout** — new `test_timeout_min` setting (`--test-timeout` flag, default 30 minutes, `0` = no limit): the test command is killed (with all its descendants) when it exceeds the limit and the task is treated as failed with the partial output, instead of hanging the run forever
- **Agent-reported token usage** — when the agent streams ACP usage updates, the run summary and the `max_tokens_per_run` budget use the agent's exact token counts (and cost, when reported) instead of the ~4-chars/token estimate; the estimator remains the fallback for agents that don't report usage
- **Touched-file context in fix prompts** — files the agent created or modified during a task (ACP writes plus edit/delete/move tool-call locations) are listed in fix-loop prompts as likely culprits, so fix attempts start from the agent's own changes instead of a fresh repo exploration
- **Interactive skill commands** that prepare a feature for `run`:
  - `create-prd <feature>` — guided brainstorming → `features/<feature>/_prd.md` (+ `adrs/`)
  - `create-tech-spec <feature>` — technical clarification → `features/<feature>/_techspec.md`
  - `create-tasks <feature>` — decomposition → `features/<feature>/_tasks.md` + `tasks/task_NN.md`
  - `create-feature <feature>` — runs the three skills in order with review gates between (`--auto`, `--force`)
- **`skills` command** — lists discovered skills and validates each `SKILL.md`
- **`init skills [--force]` command** — scaffolds `.agents/skills/` into a fresh project from the canonical bundles shipped in the JAR
- **Dynamic skill discovery** — any `.agents/skills/<name>/SKILL.md` with a `description` + `output` frontmatter becomes a shell command at startup, no Java edits required
- **Per-skill model override** — `.aicompanion.yml` accepts `skills.<name>.model: <model>` (mirrored as `AICOMPANION_SKILLS_<NAME>_MODEL` env var); resolution priority is `--model` flag → `skills.<name>.model` → env → global `model` → agent default
- **Chat sentinels** — `/abort`, `/done`, `/skip`, `/edit` (opens `$EDITOR`) intercepted by the chat loop
- **Per-skill transcript** — every chat turn appended to `features/<feature>/_<skill>.transcript.md` for debugging
- Three canonical skill bundles under `.agents/skills/` (PRD, TechSpec, tasks) with reference templates (PRD, TechSpec, ADR, question-protocol, task, task-context-schema)

### Fixed
- `reuse_session` is now honoured: `reuse_session: false` opens a fresh ACP session (with the short handoff prompt) after every task. Previously the key was documented and displayed but had no effect
- Failing test output printed to the console is now truncated with the same head+tail rule as fix-loop prompts (`fix_output_max_lines`), so a chatty test suite no longer floods the terminal
- `test_command` without the `shell:` prefix now respects single and double quotes when splitting into arguments, so commands like `mvn test -Dtest="Foo Bar"` work; previously the command was split on whitespace only
- `.idea/` (already gitignored) is no longer tracked in the repository
- Escape sequences in `Spinner` (and the new `StatusBar`) now use explicit `\u001b` literals instead of invisible raw ESC bytes embedded in the source
- `RunState` is now written atomically (write-to-temp + rename), so a crash mid-save can no longer corrupt `.aicompanion/state.yml` and silently wipe all resume state
- A test-runner I/O error no longer marks the runner thread as interrupted, which could misreport the next task as "aborted by user"; interrupts during a test run now kill the test process tree and report as interrupted

### Changed
- `AcpClientFactory` extracted from `TaskRunner` so both autonomous task execution and interactive skill chats share the same read/write/permission handlers
- `ConfigLoader.load()` overloads now accept an env-var map for testability — production behaviour unchanged
- `Help.render(skills)` replaces the static `Help.TEXT` for shell/CLI help so the discovered skill commands appear per-project

### Technical
- New `io.aicompanion.skill` package: `Skill`, `SkillLoader`, `SkillRunner`, `ChatLoop`, `Transcript`, `FeaturePipeline`, `SkillScaffolder`, `EditorLauncher`, `JLineUserInput`, `JLineGateAsker`
- Maven build maps `.agents/skills/` → `/skills/` inside the JAR so the scaffolder reads the canonical bundles from the classpath
- 54 new unit tests covering skill loading, chat loop sentinel handling, pipeline resume/force/gate logic, scaffolder file copy, and config parsing — 101 tests total

## [1.0.0] - 2026-05-12

### Added
- Initial release of `aicompanion` — an AI SDLC task runner using ACP-connected local agents
- Multi-feature mode: loop over `features/<name>/tasks/` directories to run multiple features in sequence
- Resume support: skip already-passed or failed tasks across runs using a persistent state file
- Interactive REPL shell with tab completion, Ctrl+C abort, and command history (persistent Ctrl+R)
- Model selection: choose the agent model at runtime via `config set model <name>`
- Console UX: colors, spinner, gutter for streamed agent output
- Automatic test verification after each task (`test_command` auto-detect or configurable)
- Per-task Markdown log reports written to `report_dir` (default `.aicompanion/logs`)
- Support for multiple AI agents: Claude Code, Codex, Gemini CLI, Copilot, OpenCode
- `.aicompanion.yml` config file with CLI flag and environment variable overrides
- ACP session skip when every task in a run would be resumed/skipped
- Full ACP permission model: `--yolo` auto-approval, read/write filesystem capabilities

### Changed
- Dropped legacy single-folder mode; `features/` layout is the only supported layout

### Technical
- Java 21, Maven build producing a fat JAR via `maven-shade-plugin`
- ACP SDK 0.10.0, JLine 3.26.3, SnakeYAML 2.2
- JUnit 5 + Mockito test suite
