# Changelog

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
