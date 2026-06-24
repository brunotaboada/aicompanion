# AGENTS.md

## Cursor Cloud specific instructions

`aicompanion` is a single Java 21 / Maven CLI (no web server, database, or ports — agents are spawned as stdio subprocesses). Standard build/test/run commands live in `README.md`; only the non-obvious caveats are captured below.

### Build / test / lint / run
- Build (produces `target/aicompanion-1.0.0.jar`, required by the `./aicompanion` wrapper): `mvn clean package`
- Test (153 JUnit tests): `mvn test`
- Lint: there is no separate linter configured; the compiler (`mvn compile` / `mvn package`) is the static check.
- The `.m2` dependency cache and the built JAR are not committed, so run `mvn clean package` once before using `./aicompanion`.

### Non-obvious caveats
- The interactive REPL (`./aicompanion` with no args, or any skill/`create-feature` command) builds a JLine *system* terminal and **aborts with SIGABRT if there is no TTY**. To exercise the REPL non-interactively, allocate a pseudo-TTY, e.g. `printf 'tasks\nexit\n' | script -qec "java --enable-native-access=ALL-UNNAMED -jar target/aicompanion-1.0.0.jar" /dev/null`.
- TTY-free invocations that work directly: `--version`, `--help`, and `run` / `run --dry-run-tokens` (headless task pipeline). `run --dry-run-tokens` discovers features under `features/` and estimates tokens without invoking any agent — the best smoke test that needs no external dependencies.
- Full agent-driven `run` requires an external AI agent CLI on `PATH` (e.g. `claude-code-acp`, `codex-acp`, `gemini`, `copilot`, `opencode`) **plus that provider's credentials**. None are installed by default, so `agents` reports "No AI agents detected" and end-to-end agent runs are not possible without installing an agent and supplying its API key.
