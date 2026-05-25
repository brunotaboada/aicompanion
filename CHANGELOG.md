# Changelog

## [Unreleased]

### Added
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
