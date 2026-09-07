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
    task_01.md                    ──► agent builds it ──► tests pass ──► next
    task_02.md                    ──► agent builds it ──► tests pass ──► next
  rest-api/tasks/
    task_01.md                    ──► agent builds it ──► tests pass ──► next
    task_02.md
  auth/tasks/
    task_01.md
```

`create-tasks` generates `task_NN.md` files (zero-padded, with YAML frontmatter).
Hand-written tasks can use the same `task_NN.md` pattern or any `NN-description.md`
name — the runner sorts task files alphabetically within each feature.

Drop one feature in `features/` to ship a single feature; drop several to ship
them all in one run. Features execute in alphabetical order, and within each
feature its tasks execute in alphabetical order. Resume state is scoped per
feature (`user-model/task_01.md`), so editing one feature's tasks
doesn't invalidate another's progress.

Each task file is a natural-language description of one slice of work:

```markdown
# features/rest-api/tasks/task_01.md

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

## Skills: From Idea to Task Files (Interactive)

Decomposing a feature by hand is the hard part. aicompanion ships three
**interactive skill commands** that drive the agent through a structured
conversation and produce the PRD, technical spec, and task files for you.

```
create-prd <feature>        →  features/<feature>/_prd.md       (+ adrs/adr-NNN.md)
create-tech-spec <feature>  →  features/<feature>/_techspec.md  (+ adrs/adr-NNN.md)
create-tasks <feature>      →  features/<feature>/_tasks.md     (+ tasks/task_NN.md)
create-feature <feature>    →  runs the three above with review gates between
```

Each skill is a multi-turn chat. The agent asks one question at a time (in
A/B/C/D multiple-choice format whenever the answer space is bounded); you
answer in the shell; when the agent has enough, it produces the document
and writes it to disk:

```
aicompanion> create-prd auth-system
[1/1] create-prd auth-system  (model: opus)
agent> Who are the primary users of this auth system?
       A) End users signing into a web app
       B) API consumers (other services)
       C) Both
       D) Other
you> A
agent> Got it. What's the main problem this solves for them today?
you> Frequent unwanted logouts and slow password resets.
...
agent> [write] features/auth-system/_prd.md (3,847 chars)
✓ Created features/auth-system/_prd.md
```

After `create-tasks` produces the task files, the existing `run` command
executes them autonomously — closing the loop.

### Chat sentinels

While in a skill conversation, the `you>` prompt accepts these special inputs:

| Sentinel | What it does |
|---|---|
| `/abort` | End the session immediately. No files written. |
| `/done` | Tell the agent to wrap up: produce the file with what it has, or stop with a summary of what's still needed. |
| `/skip` | Send "no preference, pick a reasonable default" — useful when a question doesn't matter to you. |
| `/edit` | Open `$EDITOR` for a multi-line answer (for pasting requirements blocks, etc.). |

### Skill flags

| Flag | Effect |
|---|---|
| `--seed <path>` | Pre-existing notes (e.g., an RFC draft). If omitted, the skill auto-detects `features/<feature>/_idea.md`. |
| `--model <name>` | One-shot model override. Beats `skills.<name>.model` from config. |

### Per-skill model

PRDs and tech specs reward heavier thinking; task decomposition is more
mechanical. `.aicompanion.yml` lets you pin a different model per skill:

```yaml
model: sonnet                    # global default

skills:
  create-prd:        { model: opus }
  create-tech-spec:  { model: opus }
  create-tasks:      { model: sonnet }
```

Resolution priority: `--model` flag → `skills.<name>.model` → `AICOMPANION_SKILLS_<NAME>_MODEL` env var → global `model` → agent default.

### The pipeline (`create-feature`)

Drives all three skills back-to-back with a review gate after each step:

```
aicompanion> create-feature auth-system
[1/3] create-prd auth-system
  ...chat...
  ✓ Created features/auth-system/_prd.md

Continue to create-tech-spec? [Y]es / [e]dit _prd.md / [n]o
> y

[2/3] create-tech-spec auth-system
  ...
```

- **Resumable**: if `_prd.md` already exists, step 1 is skipped automatically. Re-running picks up where you left off.
- `--auto` skips the gates ("trust me, just go")
- `--force` ignores existing outputs and re-runs every step
- `--seed <path>` is forwarded to the first executed step only

### Where skills live

The three canonical bundles ship inside the JAR. On a fresh project, scaffold them with:

```bash
aicompanion init skills           # writes .agents/skills/ into cwd
aicompanion init skills --force   # overwrite customised versions
```

Skills are then read from `.agents/skills/<name>/SKILL.md` (project root). Dynamic discovery means **any directory there with a `SKILL.md` becomes a shell command** — drop in `.agents/skills/create-runbook/SKILL.md` and `create-runbook` is now available, no Java edits required. Use `skills` to list and validate what's discovered.

### Per-skill transcript

Every chat turn is appended to `features/<feature>/_<skill>.transcript.md` (e.g., `_prd.transcript.md`). When a generated doc comes out wrong, the transcript shows exactly which question got which answer.

---

## Features

- **Agent-agnostic** — works with Claude Code, Codex, Gemini CLI, GitHub Copilot, OpenCode; auto-detects which is installed
- **Full permissions** — agents can read and write any file in your project without interruption (`--yolo` + client-side file handlers)
- **Lazy iteration** — task files are read one at a time, never preloaded into memory
- **Test verification** — runs your test suite after every task; stops on first failure (configurable)
- **Summary output** — agents return a concise bullet-point summary of what they did, not the full code
- **Interactive shell** — JLine3 REPL with history, tab completion, and runtime config updates
- **One-shot mode** — scriptable non-interactive `run` command for CI/CD pipelines
- **Interactive skills** — `create-prd`, `create-tech-spec`, `create-tasks`, plus the `create-feature` umbrella; dynamic discovery via `.agents/skills/`
- **Per-skill model** — pin Opus for PRD/TechSpec and Sonnet for task decomposition (or any combination)
- **Fully configurable** — settings overridable via config file, environment variables, or CLI flags
- **Per-task logs** — timestamped Markdown summary written after each task
- **Resume support** — content-hash tracking in `.aicompanion/state.yml` skips unchanged tasks across runs
- **Live status bar** — REPL `run` pins agent/model/task/fix state on the bottom row (TTY only)

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
# features/user-model/tasks/task_01.md

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
   1. task_01.md
   2. task_02.md

aicompanion> run
═══ Feature: user-model  (2 tasks) ═══
──────────────────────────────────────────────────────────
Task 1/2: user-model/task_01.md
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

To size a run before invoking an agent (no AI CLI required):

```bash
./aicompanion run --dry-run-tokens
```

---

## Task File Format

Task files are plain Markdown (or `.txt`) placed in `features/<feature-name>/tasks/` and sorted alphabetically. The `create-tasks` skill writes `task_01.md`, `task_02.md`, … with YAML frontmatter; hand-written tasks can use the same pattern or `NN-description.md` names.

```
features/storefront/tasks/
  task_01.md
  task_02.md
  task_03.md
  task_04.md
```

Each file is a natural-language description of what the agent should build:

```markdown
# features/storefront/tasks/task_02.md

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
| `verify_commands` | `[]` | List of commands run in order after each task (lint, typecheck, test, …); the first failure feeds the fix loop. Overrides `test_command` when set |
| `test_enabled` | `true` | Run tests after each task |
| `test_timeout_min` | `30` | Kill the test command after N minutes and treat it as a failure (`0` = no limit) |
| `stop_on_failure` | `true` | Stop the run if tests still fail after `max_fix_attempts` |
| `max_fix_attempts` | `3` | On test failure, feed the output back to the agent and retry up to N times (`0` disables auto-fix) |
| `session_timeout_min` | `10` | ACP session timeout per task (minutes) |
| `reuse_session` | `true` | Keep one ACP session across all tasks; `false` opens a fresh session for every task |
| `report_dir` | `.aicompanion/logs` | Directory for per-task Markdown logs |
| `report_enabled` | `true` | Write a `.md` summary log per task |
| `log_tool_calls` | `true` | Print `[read]`/`[write]`/`[perm]` events |
| `log_thoughts` | `false` | Print agent reasoning to console |
| `yolo` | `true` | Pass `--yolo` to auto-approve agent tool calls |
| `fix_output_max_lines` | `200` | Max lines of test output shown on failures and sent to fix-loop prompts (head + tail; `0` = unbounded) |
| `task_preamble_strip` | `false` | Strip everything before the first `#` heading in task files before sending |
| `compact_after_n_tasks` | `0` | Open a fresh ACP session every N tasks with a short handoff (`0` = never) |
| `pre_check_tests` | `false` | Run tests before each task; skip the agent call if they already pass |
| `max_tokens_per_run` | `0` | Stop the run when token usage exceeds this budget (`0` = unlimited). Uses agent-reported ACP usage when available, else ~4 chars/token estimate |
| `init_instructions` | `false` | Send the summary-format rules once per session instead of on every task |

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

By default the command is split into arguments (single and double quotes are respected, so `mvn test -Dtest="Foo Bar"` works) and executed directly, with no shell. This is the fastest and safest option for simple commands.

If you need pipes, `&&`, environment variable expansion, or any other shell feature, prefix the command with `shell:`:

```yaml
# Run via /bin/sh (Unix) or PowerShell (Windows)
test_command: "shell: npm test -- --watchAll=false && ./lint.sh"
```

```yaml
# Expand env vars
test_command: "shell: $JAVA_HOME/bin/java -jar test-runner.jar"
```

**Multiple commands**

Real projects often want lint + typecheck + tests. Set `verify_commands` to run several commands in order after each task:

```yaml
verify_commands:
  - npm run lint
  - npm run typecheck
  - "shell: npm test -- --watchAll=false"
```

The commands run in order and the first non-zero exit stops the sequence — its output (and the failing command's name) is what the fix loop feeds back to the agent. After each fix attempt the whole sequence runs again from the start, so a lint fix that breaks the tests is still caught. When `verify_commands` is set, `test_command` is ignored. Each entry supports the same `shell:` prefix and quoting rules as `test_command`. (As a CLI flag or env var, pass a comma-separated list: `--verify-commands "npm run lint,npm test"`.)

**On failure**

When tests fail, aicompanion feeds the output back to the agent and retries up to `max_fix_attempts` times (default `3`). Fix-loop prompts include the files the agent touched during that task (ACP writes plus edit/delete/move tool calls) so retries start from likely culprits instead of a fresh repo scan. If tests still fail after all retries and `stop_on_failure` is `true`, the run stops. The task is recorded as `FAILED` in `.aicompanion/state.yml`.

### Resume & state

Each completed task is recorded in `.aicompanion/state.yml` with a SHA-256 hash of the task file content. On the next `run`, a task is skipped when its stored hash matches the current file **and** either:

- status is `passed`, or
- status is `failed` and you did not pass `--retry-failed`

Edit a task file and its hash changes — aicompanion always re-runs edited tasks regardless of prior status. The `status` command shows `passed`, `failed`, `edited`, or `pending` per task; `reset` deletes the state file (with confirmation).

| Flag / command | Effect |
|---|---|
| `--fresh` | Delete state and run every task |
| `--retry-failed` | Resume but re-run tasks that previously failed |
| `status` | Show per-task resume labels |
| `reset` | Clear `.aicompanion/state.yml` |

State is scoped to `features_dir`. If you change that setting, prior entries are ignored and all tasks run again. Saves are atomic (write-temp + rename) so a crash mid-save cannot corrupt resume data.

**Concurrent runs:** only one `run` may hold the project lock at a time (`.aicompanion/state.lock`). A second concurrent run exits immediately with *"Another aicompanion run is in progress"*. If a process dies without releasing the lock, delete `state.lock` manually before restarting.

Example state file:

```yaml
features_dir: features
updated_at: "2026-07-13T16:00:00Z"
tasks:
  user-model/task_01.md:
    status: passed
    hash: a1b2c3…
    at: "2026-07-13T15:58:00Z"
```

### Token budgeting

Set `max_tokens_per_run` (or `--max-tokens`) to stop a run once token usage crosses a budget. When the agent streams ACP usage updates, the budget and end-of-run summary use those exact counts (and cost, when reported); otherwise aicompanion falls back to a ~4-chars/token estimate.

Use `--dry-run-tokens` to print per-task prompt estimates without spawning an agent, acquiring the run lock, or running tests — useful for tuning `task_preamble_strip`, `init_instructions`, and budget sizing:

```bash
./aicompanion run --dry-run-tokens
./aicompanion run --dry-run-tokens --task-preamble-strip --init-instructions
```

Fix-loop tokens are variable and not included in the dry-run total.

---

## Interactive Shell Commands

```
aicompanion> run [--features <dir>] [--agent <id>] [--project <dir>]
                 [--no-tests] [--no-stop-on-failure] [--log-thoughts]

aicompanion> tasks                      List features and their tasks
aicompanion> agents                     List installed AI agents
aicompanion> config                     Show current configuration
aicompanion> config set <key> <value>   Update a setting at runtime
aicompanion> status                     Show pass/fail per task across runs
aicompanion> reset                      Clear resume state

aicompanion> create-prd <feature> [--seed <path>] [--model <name>]
aicompanion> create-tech-spec <feature> [--seed <path>] [--model <name>]
aicompanion> create-tasks <feature> [--seed <path>] [--model <name>]
aicompanion> create-feature <feature> [--seed <path>] [--auto] [--force]
aicompanion> skills                     List discovered skills, validate each
aicompanion> init skills [--force]      Scaffold .agents/skills/ from bundled defaults

aicompanion> help                       Show help (includes per-project skill list)
aicompanion> exit                       Exit
```

Runtime config changes take effect immediately:

```
aicompanion> config set agent gemini
aicompanion> config set yolo false
aicompanion> config set log_thoughts true
aicompanion> run
```

During an interactive `run` on a capable TTY, a status bar is pinned to the bottom row (agent, model, current task, test/fix state). Streamed agent output scrolls above it. On dumb terminals or piped output the bar is a no-op.

Run flags such as `--fresh`, `--retry-failed`, `--dry-run-tokens`, and `--compact-after` work in both the REPL and one-shot mode (Tab completes them in the shell).

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
  TestVerifier.run()     execute test_command (or verify_commands), check exit code
      │
      ▼
  RunState.save()        atomic write to .aicompanion/state.yml (under state.lock)
      │
      ▼
  Reporter.write()       .aicompanion/logs/<task>-<timestamp>.md
```

The agent runs with full filesystem access via the client-side handlers. The `--yolo` flag additionally tells the agent to skip its own internal approval prompts.

When `compact_after_n_tasks` is set, the runner opens a fresh ACP session every N tasks and sends a short handoff listing recently completed tasks. If the handoff fails, compaction retries on the next task instead of silently continuing with a stale session.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| *Another aicompanion run is in progress* | Stale `.aicompanion/state.lock` after a crash, or a second terminal running `run` | Wait for the other run to finish, or delete `state.lock` if no process is active |
| `aicompanion: I/O error — …` on `run` | Cannot create `.aicompanion/` or open `state.lock` (permissions, read-only filesystem) | Fix directory permissions or choose a writable project root |
| Resume skipped everything unexpectedly | Malformed `state.yml` (treated as empty) or `features_dir` changed | Run `status`; use `--fresh` to start over; check for `[resume] WARNING` on startup |
| Tasks marked `edited` in `status` | Task file content changed since last run | Expected — edited tasks always re-run |
| `run` hangs during tests | Test suite never exits | Lower `test_timeout_min` or fix the test command; timeout kills the process tree |
| No status bar during REPL `run` | Non-TTY output (CI, pipe, dumb terminal) | Expected — bar requires a capable terminal |
| `--dry-run-tokens` exits 1 with I/O error | Cannot read task files under `features_dir` | Check paths and file permissions |
| *session compaction failed — will retry* | Handoff prompt to a fresh ACP session failed | Usually transient; the next task retries compaction. Lower `compact_after_n_tasks` or check agent connectivity if it persists |

---

## Project Structure

```
aicompanion/
├── .agents/skills/                Canonical skill bundles (also bundled in JAR)
│   ├── _shared/references/        Shared templates (ADR, question protocol)
│   ├── create-prd/                SKILL.md + skill-specific references/
│   ├── create-tech-spec/          SKILL.md + skill-specific references/
│   └── create-tasks/              SKILL.md + skill-specific references/
├── src/main/java/io/aicompanion/
│   ├── Main.java                  Entry point (REPL, one-shot, or skill CLI)
│   ├── Shell.java                 JLine3 interactive REPL + skill discovery
│   ├── Help.java                  Help text (static + dynamic skill section)
│   ├── TaskRunner.java            ACP session + lazy task execution loop
│   ├── RunState.java              Content-hash resume state (.aicompanion/state.yml)
│   ├── RunStateLock.java          Exclusive lock preventing concurrent runs
│   ├── TestVerifier.java          Runs test command, captures output
│   ├── Reporter.java              Writes per-task Markdown summary logs
│   ├── console/
│   │   ├── StatusBar.java         Bottom-row REPL status (DECSTBM scroll region)
│   │   └── Spinner.java           Indeterminate progress indicator
│   ├── agent/
│   │   ├── AgentSpec.java         Agent definitions (id, binary, params builder)
│   │   ├── AgentRegistry.java     Agent detection and resolution
│   │   └── AcpClientFactory.java  Shared ACP client wiring (TaskRunner + SkillRunner)
│   ├── config/
│   │   ├── Config.java            Immutable record of settings + skills map + modelFor()
│   │   └── ConfigLoader.java      4-tier config loader (CLI > env > file > default)
│   └── skill/
│       ├── Skill.java             Rendered skill (body + output path)
│       ├── SkillLoader.java       Discovers / parses / renders SKILL.md
│       ├── SkillRunner.java       Drives one skill end-to-end via ACP
│       ├── ChatLoop.java          User ↔ agent turn loop + sentinels
│       ├── Transcript.java        Per-skill chat log appended to disk
│       ├── FeaturePipeline.java   Orchestrates create-prd → tech-spec → tasks
│       ├── SkillScaffolder.java   `init skills` — copies JAR resources to disk
│       └── JLineUserInput.java    JLine-backed user input adapter
└── src/test/java/io/aicompanion/
    ├── ConfigLoaderTest.java, TaskRunnerPathsTest.java, ...
    └── skill/
        ├── SkillLoaderTest.java, ChatLoopTest.java, FeaturePipelineTest.java,
        ├── SkillScaffolderTest.java, SkillRunnerTest.java, ...
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
