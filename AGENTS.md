# AGENTS.md

## Cursor Cloud specific instructions

`aicompanion` is a single-product **Java 21 CLI** (no server, database, or web UI). Maven is the build/package manager; standard commands live in `README.md` and `pom.xml`.

- **Build / test / run** are standard Maven + the `./aicompanion` launcher; see `README.md`. Quick reference: build `mvn clean package -q`, test `mvn test`, run `./aicompanion --help`.
- **Toolchain note:** the base VM image has Java 21 and Node, but **not Maven**. Maven 3.9.9 is installed to `/opt/apache-maven-3.9.9` (symlinked at `/usr/local/bin/mvn`) during environment setup and persists in the snapshot; the update script reinstalls it only if missing.
- **`./aicompanion` requires the fat JAR to exist first** (`target/aicompanion-1.0.0.jar`). If you see "not built yet", run `mvn clean package -q`. The update script does not build the JAR (build steps are intentionally kept out of it).
- **End-to-end `run` / interactive skills need an external AI agent CLI on PATH** (one of: Claude Code ACP adapter, Codex, Gemini, Copilot, OpenCode) plus that agent's own provider credentials. None are installed by default; without one, `run` fails with "No AI agent found on PATH." To exercise the run loop *without* an agent, use `./aicompanion run --dry-run-tokens` (feature discovery + prompt building, no agent invoked).
- Interactive skill commands (`create-prd`, etc.) require a TTY.
