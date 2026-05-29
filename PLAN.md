# aicompanion — AI SDLC Task Runner

## What it does

`aicompanion` scans a `features/` directory, sends each task file sequentially
to a locally-installed AI agent via the **Agent Control Protocol (ACP)**,
lets the agent build the project with full filesystem permissions, verifies tests pass
after each task, and continues until all tasks across all features are complete.

## Usage

```bash
# Interactive REPL
./aicompanion

# One-shot
./aicompanion run --features features --project /path/to/project

# With a specific agent
./aicompanion run --agent gemini --features features

# Estimate token cost before running
./aicompanion run --dry-run-tokens
```

## Interactive commands

| Command | Description |
|---------|-------------|
| `run [flags]` | Execute all tasks through the agent |
| `tasks` | List features and task files |
| `status` | Show per-task pass/fail/pending state |
| `agents` | List installed AI agents |
| `config` | Show current configuration |
| `config set <key> <value>` | Update a setting at runtime (accumulated across multiple calls) |
| `skills` | List available skill bundles |
| `create-feature <name>` | Run the full PRD → TechSpec → Tasks pipeline |
| `init skills` | Scaffold canonical skill bundles into `.agents/skills/` |
| `reset` | Delete the resume-state file |
| `help` | Show help |
| `exit` / `quit` | Exit |

## Configuration

Settings are read from `.aicompanion.yml` in the working directory.
CLI flags and environment variables (`AICOMPANION_<KEY>`) override the file.

| Key | Default | Description |
|-----|---------|-------------|
| `agent` | auto-detect | Agent: `claude`, `codex`, `gemini`, `copilot`, `opencode` |
| `model` | agent default | Model name or short token (e.g. `sonnet`) |
| `agent_extra_args` | `[]` | Extra CLI args appended to the agent command |
| `features_dir` | `features` | Top-level directory containing one subfolder per feature |
| `task_extensions` | `[md, txt]` | File extensions treated as tasks |
| `task_sort` | `alphabetical` | Sort: `alphabetical`, `none` |
| `project_dir` | `.` | Project root passed to the agent session |
| `test_command` | auto-detect | Shell command to run tests |
| `test_enabled` | `true` | Run tests after each task |
| `stop_on_failure` | `true` | Stop on first test failure |
| `max_fix_attempts` | `3` | Fix-loop retries per task |
| `session_timeout_min` | `10` | ACP session timeout per task (minutes) |
| `report_dir` | `.aicompanion/logs` | Directory for per-task markdown logs |
| `report_enabled` | `true` | Write markdown log per task |
| `log_tool_calls` | `true` | Print agent tool calls to console |
| `log_thoughts` | `false` | Print agent reasoning to console |
| `yolo` | `true` | Auto-approve agent tool calls (`--yolo` flag) |
| `fix_output_max_lines` | `200` | Max lines of test output sent to fix-loop prompts |
| `task_preamble_strip` | `false` | Strip everything before the first `#` heading in task files |
| `compact_after_n_tasks` | `0` | Open a fresh ACP session every N tasks (0 = never) |
| `pre_check_tests` | `false` | Run tests before each task; skip if they already pass |
| `max_tokens_per_run` | `0` | Stop when estimated token usage exceeds this (0 = unlimited) |
| `init_instructions` | `false` | Send format rules once per session instead of per task |

## Features directory layout

```
features/
  user-auth/
    tasks/
      01-user-model.md
      02-auth-routes.md
      03-tests.md
  payment/
    tasks/
      01-stripe-integration.md
      02-webhook-handler.md
```

Each feature subfolder must contain a `tasks/` subdirectory. Features without it
are silently skipped. Features and tasks within them are sorted alphabetically.

## Task file format

Plain Markdown (or `.txt`) files. Each file describes what the agent should do:

```markdown
Create a User model in src/main/java/com/example/User.java with fields:
- id (Long, primary key)
- email (String, not null, unique)
- createdAt (LocalDateTime, auto-set)

Add a JPA repository interface UserRepository.
```

## Skill pipeline

Skills are interactive PRD / TechSpec / task-decomposition sessions.
Each skill lives in `.agents/skills/<name>/SKILL.md` and is driven by
`SkillRunner` + `ChatLoop` over an ACP session.

```
aicompanion create-feature user-auth
  → create-prd      (features/user-auth/_prd.md)
  → create-tech-spec (features/user-auth/_techspec.md)
  → create-tasks    (features/user-auth/tasks/*.md)
```

Skills can also be run individually:
```bash
aicompanion create-prd user-auth
```

## ACP Permission model

The agent runs with full permissions:
- `--yolo` flag: agent auto-approves its own tool calls
- `readTextFileHandler`: serves any file the agent requests
- `writeTextFileHandler`: writes any path the agent produces
- `requestPermissionHandler`: auto-selects ALLOW_ALWAYS for any remaining prompts
- `ClientCapabilities(FileSystemCapability(read=true, write=true))`

## Architecture

```
Main → Shell (interactive REPL) or TaskRunner (one-shot --run)
         ↓
     TaskRunner
       - BatchResolver.resolveBatches()   (one Batch per feature with tasks/)
       - for each Batch:
           for each task path (sorted):
             Files.readString(path)        (lazy — one file at a time)
             + summary instruction appended to prompt
             → AcpSyncClient.prompt()
             → stream AgentMessageChunk to console + summaryBuf
             → Reporter.write()
             → TestVerifier.run()
             → fix-loop up to maxFixAttempts
         - RunState persisted after each task for resume support

     SkillRunner (interactive, per-feature)
       - ChatLoop drives back-and-forth until /done or /abort
       - Transcript records every turn to <feature>/_<skill>.transcript.md
```

Key classes:
- `BatchResolver` — scans `features_dir`, returns `List<Batch>` sorted by feature name
- `RunState` — SHA-256-based resume: skips tasks whose content hasn't changed
- `AgentConsole` — streaming gutter renderer, spinner, space-bar output toggle
- `AgentRegistry` — detects installed agents; `findModel`/`modelLabel` for model resolution
- `Config` — record of all settings; `Config.KEYS` is the authoritative key list

## Build

```bash
cd aicompanion
mvn clean package -q
chmod +x aicompanion
./aicompanion
```

Java 21 required.
