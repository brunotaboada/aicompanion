# aicompanion — AI SDLC Task Runner

## What it does

`aicompanion` reads task files from a folder (e.g. `feature/tasks/`), sends each one
sequentially to a locally-installed AI agent via the **Agent Control Protocol (ACP)**,
lets the agent build the project with full filesystem permissions, verifies tests pass
after each task, and continues until all tasks are complete.

## Usage

```bash
# Interactive REPL
./aicompanion

# One-shot
./aicompanion run --tasks feature/tasks --project /path/to/project

# With a specific agent
./aicompanion run --agent gemini --tasks feature/tasks
```

## Interactive commands

| Command | Description |
|---------|-------------|
| `run [flags]` | Execute all tasks through the agent |
| `tasks` | List task files in the configured directory |
| `agents` | List installed AI agents |
| `config` | Show current configuration |
| `config set <key> <value>` | Update a setting at runtime |
| `help` | Show help |
| `exit` / `quit` | Exit |

## Configuration

Settings are read from `.aicompanion.yml` in the working directory.
CLI flags and environment variables (`AICOMPANION_<KEY>`) override the file.

| Key | Default | Description |
|-----|---------|-------------|
| `agent` | auto-detect | Agent: `claude`, `codex`, `gemini`, `copilot`, `opencode` |
| `agent_extra_args` | `[]` | Extra CLI args appended to the agent command |
| `tasks_dir` | `feature/tasks` | Folder with `.md` / `.txt` task files |
| `task_extensions` | `[md, txt]` | File extensions treated as tasks |
| `task_sort` | `alphabetical` | Sort: `alphabetical`, `none` |
| `project_dir` | `.` | Project root passed to the agent session |
| `test_command` | auto-detect | Shell command to run tests |
| `test_enabled` | `true` | Run tests after each task |
| `stop_on_failure` | `true` | Stop on first test failure |
| `session_timeout_min` | `10` | ACP session timeout per task (minutes) |
| `report_dir` | `.aicompanion/logs` | Directory for per-task markdown logs |
| `report_enabled` | `true` | Write markdown log per task |
| `log_tool_calls` | `true` | Print agent tool calls to console |
| `log_thoughts` | `false` | Print agent reasoning to console |
| `yolo` | `true` | Auto-approve agent tool calls (`--yolo` flag) |

## Task file format

Plain Markdown files in `tasks_dir`, sorted alphabetically:

```
feature/tasks/
  01-create-model.md
  02-add-routes.md
  03-write-tests.md
```

Each file describes what the agent should do:

```markdown
Create a User model in src/main/java/com/example/User.java with fields:
- id (Long, primary key)
- email (String, not null, unique)
- createdAt (LocalDateTime, auto-set)

Add a JPA repository interface UserRepository.
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
Main → Shell (interactive) or TaskRunner (one-shot)
         ↓
     TaskRunner
       - resolveTaskPaths()   (sorted paths only, no content loaded)
       - for each path:
           Files.readString(path)   (lazy — one file at a time)
           + summary instruction appended to prompt
           → AcpSyncClient.prompt()
           → stream AgentMessageChunk to console + summaryBuf
           → reporter.write()
           → TestVerifier.run()
```

## Build

```bash
cd aicompanion
mvn clean package -q
chmod +x aicompanion
./aicompanion
```

Java 21 required (highest available: openjdk 21.0.10).
