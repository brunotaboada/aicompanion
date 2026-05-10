# aicompanion

> From idea to implementation in one flow — decompose your feature into tasks, delegate each one to a local AI agent, and ship it.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![ACP SDK](https://img.shields.io/badge/ACP--SDK-0.10.0-green.svg)](https://github.com/agentclientprotocol/java-sdk)

---

## The context

Building software with AI is most effective when work is broken into small, focused tasks — not dumped into a single massive prompt. But orchestrating that decomposition manually is tedious: create a task, send it to the agent, verify it worked, repeat.

`aicompanion` automates the **task decomposition loop** that sits at the heart of AI-assisted software development. You define the breakdown; it handles the execution end to end.

---

## Idea → Implementation in One Flow

The entire journey — from a feature idea to working, tested code — happens in a single continuous run:

```
1. DECOMPOSE   You break your feature into ordered task files (plain Markdown)
       │
       ▼
2. DELEGATE    aicompanion sends each task to a locally-installed AI agent
               (Claude Code, Codex, Gemini CLI, Copilot, OpenCode)
       │
       ▼
3. BUILD       The agent reads and writes files in your project with full permissions
       │
       ▼
4. VERIFY      aicompanion runs your test suite after each task
       │        ✓ passes → next task
       │        ✗ fails  → stop and report
       ▼
5. LOG         A timestamped Markdown summary is written for every completed task
       │
       ▼
6. REPEAT      Steps 2–5 repeat until all tasks are done
```

The result: your feature is implemented, task by task, with tests green at every step — without you babysitting the agent.

---

## Task Decomposition in Practice

The key discipline `aicompanion` enforces is **explicit task decomposition before execution**. You think through each feature upfront, split it into independently-deliverable pieces, and express each one as a Markdown file under `features/<feature-name>/tasks/`.

```
features/
  user-model/tasks/
    01-create-entity.md           ──► agent builds it ──► tests pass ──► next
    02-add-repository.md          ──► agent builds it ──► tests pass ──► next
  rest-api/tasks/
    01-add-endpoints.md           ──► agent builds it ──► tests pass ──► next
    02-add-validation.md
  auth/tasks/
    01-add-login.md
```

Drop one feature in `features/` to ship a single feature; drop several to ship
them all in one run. Features execute in alphabetical order, and within each
feature its tasks execute in alphabetical order. Resume state is scoped per
feature (`user-model/01-create-entity.md`), so editing one feature's tasks
doesn't invalidate another's progress.

Each task file is a natural-language description of one slice of work:

```markdown
# features/rest-api/tasks/01-add-endpoints.md

Add REST endpoints for the User entity:
- GET  /users/{id}   → return user as JSON
- POST /users        → create user, validate email is unique
- DELETE /users/{id} → soft-delete (set deletedAt)

Use Spring MVC. Place controllers under `src/main/java/com/example/web/`.
```

This decomposition-first approach gives you:
- **Auditability** — each task maps to a verifiable, tested change
- **Recoverability** — a failure stops at one task, not the whole feature
- **Reusability** — task files are plain text you can version, share, and re-run

---

## Features

- **Agent-agnostic** — works with Claude Code, Codex, Gemini CLI, GitHub Copilot, OpenCode; auto-detects which is installed
- **Full permissions** — agents can read and write any file in your project without interruption (`--yolo` + client-side file handlers)
- **Lazy iteration** — task files are read one at a time, never preloaded into memory
- **Test verification** — runs your test suite after every task; stops on first failure (configurable)
- **Summary output** — agents return a concise bullet-point summary of what they did, not the full code
- **Interactive shell** — JLine3 REPL with history, tab completion, and runtime config updates
- **One-shot mode** — scriptable non-interactive `run` command for CI/CD pipelines
- **Fully configurable** — 16 settings, overridable via config file, environment variables, or CLI flags
- **Per-task logs** — timestamped Markdown summary written after each task

---

## Requirements

- **Java 21** or later
- **Maven 3.9+** (for building from source)
- At least one AI agent installed locally:
  - [Claude Code](https://claude.ai/code) — install ACP adapter: `npm install -g @zed-industries/claude-code-acp`
  - [OpenAI Codex](https://github.com/openai/codex) — `codex-acp`
  - [Gemini CLI](https://github.com/google-gemini/gemini-cli) — `gemini`
  - [GitHub Copilot CLI](https://docs.github.com/en/copilot/github-copilot-in-the-cli) — `copilot`
  - [OpenCode](https://github.com/sst/opencode) — `opencode`

---

## Installation

### Build from source

```bash
git clone https://github.com/brunotaboada/aicompanion.git
cd aicompanion
mvn clean package -q
chmod +x aicompanion
```

This produces `target/aicompanion-1.0.0.jar` and a `./aicompanion` wrapper script.

---

## Quick Start

**1. Decompose each feature into task files under `features/`**

```bash
mkdir -p features/user-model/tasks
```

```markdown
# features/user-model/tasks/01-create-model.md

Create a `User` class in `src/main/java/com/example/User.java` with:
- `id` (Long, primary key)
- `email` (String, not null, unique)
- `createdAt` (LocalDateTime, auto-set on insert)

Add a Spring Data JPA repository interface `UserRepository`.
```

Add as many features as you like — each one is a sibling folder with its own
`tasks/`. The runner ships them in alphabetical order.

**2. Launch the interactive shell and run**

```bash
./aicompanion
```

```
aicompanion v1.0.0
agent: auto-detect  |  features: features
Type 'help' for available commands.

aicompanion> agents
  ✓ claude         (claude-code-acp)
  ✓ gemini         (gemini)

aicompanion> tasks
Features in features (1 features, 2 tasks):
  user-model:
   1. 01-create-model.md
   2. 02-add-endpoints.md

aicompanion> run
═══ Feature: user-model  (2 tasks) ═══
──────────────────────────────────────────────────────────
Task 1/2: user-model/01-create-model.md
──────────────────────────────────────────────────────────
[write] src/main/java/com/example/User.java (842 chars)
[write] src/main/java/com/example/UserRepository.java (312 chars)
[stop] end_turn

Running tests...
✓ Tests passed
...
All 2 tasks complete.
```

**3. Or run non-interactively**

```bash
./aicompanion run --features features --project /path/to/project
```

---

## Task File Format

Task files are plain Markdown (or `.txt`) placed in `features/<feature-name>/tasks/` and sorted alphabetically. Prefix filenames with numbers to control execution order.

```
features/storefront/tasks/
  01-setup-database.md
  02-create-models.md
  03-add-api-routes.md
  04-write-unit-tests.md
```

Each file is a natural-language description of what the agent should build:

```markdown
# features/storefront/tasks/02-create-models.md

Create JPA entity classes for the following tables:

**Product**
- id (Long, auto-generated)
- name (String, max 200 chars, not null)
- price (BigDecimal, not null)
- stock (Integer, default 0)

**Order**
- id (Long, auto-generated)
- userId (Long, foreign key → User)
- createdAt (LocalDateTime)
- status (enum: PENDING, SHIPPED, DELIVERED)

Add corresponding Spring Data JPA repositories for both entities.
Place all files under `src/main/java/com/example/model/`.
```

After completing each task, the agent outputs a concise summary of exactly what it changed — no full code dumps.

---

## Supported Agents

| Agent | Binary | Auto-approve flag |
|-------|--------|-------------------|
| Claude Code | `claude-code-acp` | — (client-side auto-approve) |
| OpenAI Codex | `codex-acp` | `--yolo` |
| Gemini CLI | `gemini` | `--yolo` |
| GitHub Copilot | `copilot` | `--yolo` |
| OpenCode | `opencode` | — |

Detection order (first found wins): Claude → Codex → Gemini → Copilot → OpenCode.  
Pin a specific agent with `--agent <id>` or `agent: <id>` in the config file.

---

## Configuration

Copy `.aicompanion.yml.example` to `.aicompanion.yml` in your project root:

```bash
cp .aicompanion.yml.example .aicompanion.yml
```

### All settings

| Key | Default | Description |
|-----|---------|-------------|
| `agent` | auto-detect | Agent id: `claude`, `codex`, `gemini`, `copilot`, `opencode` |
| `model` | agent default | Pin a model the agent advertises (e.g. `sonnet`, `opus`, `claude-sonnet-4-6`); matched permissively |
| `agent_extra_args` | `[]` | Extra CLI args appended to the agent command |
| `features_dir` | `features` | Parent dir of feature subfolders. Each feature must contain a `tasks/` child. The runner ships every feature found here. |
| `task_extensions` | `[md, txt]` | File extensions treated as tasks |
| `task_sort` | `alphabetical` | Sort order: `alphabetical` or `none` |
| `project_dir` | `.` | Project root passed to the agent ACP session |
| `test_command` | auto-detect | Command to run your tests |
| `test_enabled` | `true` | Run tests after each task |
| `stop_on_failure` | `true` | Stop the run if tests still fail after `max_fix_attempts` |
| `max_fix_attempts` | `3` | On test failure, feed the output back to the agent and retry up to N times (`0` disables auto-fix) |
| `session_timeout_min` | `10` | ACP session timeout per task (minutes) |
| `reuse_session` | `true` | Keep one ACP session across all tasks |
| `report_dir` | `.aicompanion/logs` | Directory for per-task Markdown logs |
| `report_enabled` | `true` | Write a `.md` summary log per task |
| `log_tool_calls` | `true` | Print `[read]`/`[write]`/`[perm]` events |
| `log_thoughts` | `false` | Print agent reasoning to console |
| `yolo` | `true` | Pass `--yolo` to auto-approve agent tool calls |

### Override priority

Settings are resolved in this order (highest wins):

```
CLI flag  >  AICOMPANION_<KEY> env var  >  .aicompanion.yml  >  built-in default
```

Examples:

```bash
# CLI flag
./aicompanion run --agent gemini --no-tests

# Environment variable
AICOMPANION_AGENT=gemini AICOMPANION_LOG_THOUGHTS=true ./aicompanion run

# Config file
echo "agent: gemini" >> .aicompanion.yml
```

### Test command

`test_command` is the command aicompanion runs after every task to verify the code works. A non-zero exit code counts as failure.

**How to set it**

In `.aicompanion.yml` (recommended):
```yaml
test_command: mvn test -q
```

As a CLI flag:
```bash
./aicompanion run --test_command "mvn test -q"
```

As an environment variable:
```bash
AICOMPANION_TEST_COMMAND="mvn test -q" ./aicompanion run
```

**Auto-detection**

If `test_command` is not set, aicompanion detects it from your project root:

| File present | Command used |
|---|---|
| `pom.xml` | `mvn test -q` |
| `build.gradle` | `gradle test` |
| `package.json` | `npm test` |
| `Makefile` | `make test` |

**Shell mode**

By default the command is split on whitespace and executed directly (no shell). This is the fastest and safest option for simple commands.

If you need pipes, `&&`, environment variable expansion, or any other shell feature, prefix the command with `shell:`:

```yaml
# Run via /bin/sh (Unix) or PowerShell (Windows)
test_command: "shell: npm test -- --watchAll=false && ./lint.sh"
```

```yaml
# Expand env vars
test_command: "shell: $JAVA_HOME/bin/java -jar test-runner.jar"
```

**On failure**

When tests fail, aicompanion feeds the output back to the agent and retries up to `max_fix_attempts` times (default `3`). If tests still fail after all retries and `stop_on_failure` is `true`, the run stops. The task is recorded as `FAILED` in `.aicompanion/state.yml`.

On the next `run`, failed tasks are skipped by default. Use `--retry-failed` to re-run them, or `--fresh` to wipe all state and start over.

---

## Interactive Shell Commands

```
aicompanion> run [--features <dir>] [--agent <id>] [--project <dir>]
                 [--no-tests] [--no-stop-on-failure] [--log-thoughts]

aicompanion> tasks                      List features and their tasks
aicompanion> agents                     List installed AI agents
aicompanion> config                     Show current configuration
aicompanion> config set <key> <value>   Update a setting at runtime
aicompanion> help                       Show help
aicompanion> exit                       Exit
```

Runtime config changes take effect immediately:

```
aicompanion> config set agent gemini
aicompanion> config set yolo false
aicompanion> config set log_thoughts true
aicompanion> run
```

---

## How It Works (Internals)

```
./aicompanion run
      │
      ▼
AgentRegistry          detect installed agents (PATH scan)
      │ AgentSpec
      ▼
StdioAcpClientTransport   launch agent as subprocess (ACP Java SDK 0.10.0)
      │ stdin/stdout
      ▼
AcpSyncClient
  .initialize()          ACP handshake + declare FileSystemCapability(read, write)
  .newSession(cwd)       open session on project root
      │
      ▼  for each task file (lazy — read one at a time):
  .prompt(content + summary instruction)
      │
      ├── readTextFileHandler    serve any file the agent reads
      ├── writeTextFileHandler   write any file the agent produces
      └── requestPermissionHandler  auto-approve (prefer ALLOW_ALWAYS)
      │
      ▼
  stream AgentMessageChunk to console + summaryBuf
      │
      ▼
  TestVerifier.run()     execute test_command, check exit code
      │
      ▼
  Reporter.write()       .aicompanion/logs/<task>-<timestamp>.md
```

The agent runs with full filesystem access via the client-side handlers. The `--yolo` flag additionally tells the agent to skip its own internal approval prompts.

---

## Project Structure

```
aicompanion/
├── src/main/java/io/aicompanion/
│   ├── Main.java                  Entry point (REPL or one-shot)
│   ├── Shell.java                 JLine3 interactive REPL
│   ├── TaskRunner.java            ACP session + lazy task execution loop
│   ├── TaskStatus.java            Enum: PENDING, RUNNING, PASSED, FAILED, SKIPPED
│   ├── TestVerifier.java          Runs test command, captures output
│   ├── Reporter.java              Writes per-task Markdown summary logs
│   ├── agent/
│   │   ├── AgentSpec.java         Agent definitions (id, binary, params builder)
│   │   └── AgentRegistry.java     Agent detection and resolution
│   └── config/
│       ├── Config.java            Immutable record of all 16 settings
│       └── ConfigLoader.java      4-tier config loader (CLI > env > file > default)
└── src/test/java/io/aicompanion/
    ├── ConfigLoaderTest.java
    └── TaskRunnerPathsTest.java
```

---

## Contributing

Contributions are welcome! Please open an issue first to discuss what you'd like to change.

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-change`
3. Commit your changes: `git commit -m "Add my change"`
4. Push and open a Pull Request

To add support for a new AI agent, add an entry to `AgentSpec.ALL` in `AgentSpec.java`.

---

## License

[MIT](LICENSE) © 2026 Bruno Taboada
