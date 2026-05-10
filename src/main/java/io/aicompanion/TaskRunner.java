package io.aicompanion;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionCancelled;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.ModelInfo;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionModelState;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModelRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdateNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileResponse;
import io.aicompanion.agent.AgentRegistry;
import io.aicompanion.agent.AgentSpec;
import io.aicompanion.config.Config;
import io.aicompanion.console.Ansi;
import io.aicompanion.console.Spinner;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class TaskRunner {

    private final Config     config;
    private final RunOptions runOptions;

    /**
     * Holds the in-progress task's text output.
     * AtomicReference lets the sessionUpdateConsumer lambda (created once at
     * client-build time) write into whichever StringBuilder is current.
     */
    private final AtomicReference<StringBuilder> summaryBuf =
        new AtomicReference<>(new StringBuilder());

    /** Active "thinking" spinner; cleared as soon as the agent streams its first chunk. */
    private final AtomicReference<Spinner> activeSpinner = new AtomicReference<>();

    /** Gutter prefix prepended to each line of streamed agent text. */
    private static final String GUTTER = Ansi.dim("│ ");

    /** True when the next chunk character will land at the start of a fresh line. */
    private volatile boolean agentLineStart = true;

    public TaskRunner(Config config) {
        this(config, RunOptions.defaults());
    }

    public TaskRunner(Config config, RunOptions runOptions) {
        this.config     = config;
        this.runOptions = runOptions;
    }

    /**
     * One feature's worth of tasks. {@code featureName} is empty in legacy
     * single-folder mode; otherwise it's the subdirectory name under
     * {@code features_dir} and is used as the state-key prefix so resume
     * tracks "feature-a/01-task.md" distinctly from "feature-b/01-task.md".
     */
    record Batch(String featureName, List<Path> taskPaths) {
        String stateKeyPrefix() { return featureName.isEmpty() ? "" : featureName + "/"; }
    }

    public void run() throws Exception {
        AgentSpec spec    = AgentRegistry.resolve(config);
        Path      projDir = Path.of(config.projectDir()).toAbsolutePath();
        var       verifier = new TestVerifier(config.testCommand(), projDir);
        var       reporter = new Reporter(config);

        List<Batch> batches = resolveBatches();
        int totalTasks = batches.stream().mapToInt(b -> b.taskPaths().size()).sum();

        if (totalTasks == 0) {
            System.out.println("No task files found in: "
                + (featuresMode() ? config.featuresDir() : config.tasksDir()));
            return;
        }

        if (runOptions.fresh()) {
            try { RunState.delete(); } catch (IOException ignore) {}
        }
        RunState state = RunState.load();
        state.setTasksDir(featuresMode() ? config.featuresDir() : config.tasksDir());
        announceResume(state, batches);

        System.out.println("Agent  : " + spec.id());
        if (featuresMode()) {
            System.out.println("Features: " + batches.size()
                + "  (" + totalTasks + " tasks)");
        } else {
            System.out.println("Tasks  : " + totalTasks);
        }
        System.out.println("Dir    : " + projDir);
        System.out.println();

        var transport = new StdioAcpClientTransport(spec.params(config).get());

        try (AcpSyncClient client = buildClient(transport)) {

            client.initialize(new InitializeRequest(1,
                new ClientCapabilities(new FileSystemCapability(true, true), false)));

            var session = client.newSession(
                new NewSessionRequest(projDir.toString(), List.of()));
            System.out.println("Session: " + session.sessionId());
            selectModel(client, session.sessionId(), session.models());
            System.out.println();

            int taskOffset = 0;
            for (Batch batch : batches) {
                if (Thread.currentThread().isInterrupted()) {
                    System.out.println(Ansi.yellow("Aborted by user."));
                    return;
                }
                if (!batch.featureName().isEmpty()) {
                    System.out.println(Ansi.bold(Ansi.cyan(
                        "═══ Feature: " + batch.featureName()
                            + "  (" + batch.taskPaths().size() + " task"
                            + (batch.taskPaths().size() == 1 ? "" : "s") + ") ═══")));
                }
                boolean keepGoing = runTaskBatch(client, session.sessionId(), state,
                    batch, taskOffset, totalTasks, verifier, reporter);
                taskOffset += batch.taskPaths().size();
                if (!keepGoing) return;
            }

            System.out.println(Ansi.dim("─".repeat(60)));
            System.out.println(Ansi.green("All " + totalTasks + " tasks complete."));
        } finally {
            stopActiveSpinner();
        }
    }

    /**
     * Run all tasks in one batch through an existing ACP session.
     *
     * @param taskOffset display offset so the "Task N/total" counter remains
     *                   global across features
     * @return false when stop-on-failure was triggered (caller must abort the run)
     */
    private boolean runTaskBatch(AcpSyncClient client, String sessionId,
                                  RunState state, Batch batch,
                                  int taskOffset, int totalTasks,
                                  TestVerifier verifier, Reporter reporter) throws IOException {
        List<Path> taskPaths = batch.taskPaths();
        String     prefix    = batch.stateKeyPrefix();

        for (int i = 0; i < taskPaths.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                System.out.println(Ansi.yellow(
                    "Aborted by user — stopped before task " + (taskOffset + i + 1) + "."));
                return false;
            }

            Path   taskPath = taskPaths.get(i);
            String taskName = taskPath.getFileName().toString();
            String stateKey = prefix + taskName;
            int    globalIdx = taskOffset + i + 1;
            String displayName = prefix.isEmpty() ? taskName : prefix + taskName;

            String taskContent = Files.readString(taskPath);
            String taskHash    = RunState.hash(taskContent);

            if (state.shouldSkip(stateKey, taskHash, runOptions.retryFailed())) {
                System.out.printf("%s %d/%d: %s %s%n",
                    Ansi.bold("Task"), globalIdx, totalTasks, Ansi.cyan(displayName),
                    Ansi.green("✓ skipped (already "
                        + state.get(stateKey).status().name().toLowerCase() + ")"));
                continue;
            }

            System.out.println(Ansi.dim("─".repeat(60)));
            System.out.printf("%s %d/%d: %s%n",
                Ansi.bold("Task"), globalIdx, totalTasks, Ansi.cyan(displayName));
            System.out.println(Ansi.dim("─".repeat(60)));

            summaryBuf.set(new StringBuilder());

            String prompt = taskContent + "\n\n" +
                "When you are done, end your response with a concise summary " +
                "of what you changed or created. Format requirements:\n" +
                "  • Precede the summary with a blank line.\n" +
                "  • 3–5 bullet points, each on its own line, starting with `- `.\n" +
                "  • Do NOT include a heading like `## Summary` or `# Summary`.\n" +
                "  • Do NOT repeat any code.\n" +
                "  • Do NOT add commentary after the bullets.";

            agentLineStart = true;
            Spinner thinking = new Spinner("agent thinking");
            activeSpinner.set(thinking);
            thinking.start();

            var response = client.prompt(new PromptRequest(
                sessionId, List.of(new TextContent(prompt))));

            stopActiveSpinner();
            closeAgentGutter();
            System.out.println(Ansi.dim("[stop] " + response.stopReason()));

            reporter.write(displayName, summaryBuf.get().toString());

            if (config.testEnabled()) {
                TestVerifier.Result result = runTestsWithSpinner(verifier, "Running tests");

                int attempt = 0;
                while (!result.passed() && attempt < config.maxFixAttempts()) {
                    attempt++;
                    System.out.printf("%s Tests FAILED — fix attempt %d/%d%n",
                        Ansi.red("✗"), attempt, config.maxFixAttempts());
                    System.out.println(result.output());

                    String fixPrompt =
                        "Your previous changes broke the test suite. " +
                        "The test command `" + config.testCommand() + "` failed " +
                        "with the output below.\n\n" +
                        "----- TEST OUTPUT -----\n" +
                        result.output() +
                        "\n----- END OUTPUT -----\n\n" +
                        "Diagnose the failure, fix it (edit existing code, do not " +
                        "delete or weaken the failing tests), then end your response " +
                        "with a concise summary. Format requirements:\n" +
                        "  • Precede the summary with a blank line.\n" +
                        "  • 3–5 bullet points, each on its own line, starting with `- `.\n" +
                        "  • Do NOT include a heading like `## Summary` or `# Summary`.\n" +
                        "  • Do NOT repeat any code.";

                    summaryBuf.set(new StringBuilder());
                    agentLineStart = true;
                    Spinner fixThinking = new Spinner("agent thinking (fix attempt " + attempt + ")");
                    activeSpinner.set(fixThinking);
                    fixThinking.start();

                    var fixResp = client.prompt(new PromptRequest(
                        sessionId, List.of(new TextContent(fixPrompt))));

                    stopActiveSpinner();
                    closeAgentGutter();
                    System.out.println(Ansi.dim("[stop] " + fixResp.stopReason()));

                    reporter.write(displayName + ".fix" + attempt,
                        summaryBuf.get().toString());

                    result = runTestsWithSpinner(verifier, "Re-running tests");
                }

                if (result.passed()) {
                    System.out.println(attempt == 0
                        ? Ansi.green("✓ Tests passed") + "\n"
                        : Ansi.green("✓ Tests passed")
                            + " after " + attempt + " fix attempt(s)\n");
                    state.markPassed(stateKey, taskHash);
                } else {
                    System.out.printf("%s Tests still FAILED after %d fix attempt(s)%n",
                        Ansi.red("✗"), config.maxFixAttempts());
                    System.out.println(result.output());
                    state.markFailed(stateKey, taskHash);
                    if (config.stopOnFailure()) {
                        persistState(state);
                        System.out.println(Ansi.yellow(
                            "Stopping — fix the failure before continuing."));
                        return false;
                    }
                }
            } else {
                state.markPassed(stateKey, taskHash);
            }
            persistState(state);
        }
        return true;
    }

    private TestVerifier.Result runTestsWithSpinner(TestVerifier verifier, String label) {
        Spinner s = new Spinner(label);
        s.start();
        try {
            return verifier.run();
        } finally {
            s.stop();
        }
    }

    /** Save state, but never let an I/O hiccup abort the run. */
    private void persistState(RunState state) {
        try {
            state.save();
        } catch (IOException e) {
            System.err.println(Ansi.yellow(
                "Warning: could not persist resume state — " + e.getMessage()));
        }
    }

    /**
     * Print a one-line summary of what auto-resume will skip, plus a hint about
     * --fresh / --retry-failed. Stays silent when there is nothing to resume.
     */
    private void announceResume(RunState state, List<Batch> batches) {
        if (state.tasks().isEmpty()) return;

        int passedSkip = 0, failedSkip = 0, total = 0;
        for (Batch batch : batches) {
            String prefix = batch.stateKeyPrefix();
            for (Path p : batch.taskPaths()) {
                total++;
                String key = prefix + p.getFileName().toString();
                RunState.TaskState t = state.get(key);
                if (t == null) continue;
                String currentHash;
                try { currentHash = RunState.hash(Files.readString(p)); }
                catch (IOException e) { continue; }
                if (state.shouldSkip(key, currentHash, runOptions.retryFailed())) {
                    if (t.status() == RunState.Status.PASSED) passedSkip++;
                    else                                      failedSkip++;
                }
            }
        }
        int totalSkip = passedSkip + failedSkip;
        if (totalSkip == 0) return;

        StringBuilder msg = new StringBuilder("[resume] ");
        msg.append(passedSkip).append(" passed");
        if (failedSkip > 0) msg.append(", ").append(failedSkip).append(" failed");
        msg.append(" — skipping ").append(totalSkip).append('/').append(total);
        msg.append(". Use --fresh");
        if (failedSkip > 0) msg.append(" or --retry-failed");
        msg.append(" to re-run.");
        System.out.println(Ansi.dim(msg.toString()));
    }

    private void stopActiveSpinner() {
        Spinner s = activeSpinner.getAndSet(null);
        if (s != null) s.stop();
    }

    /**
     * Print streamed agent text with a dim "│ " gutter at the start of each
     * line, so model output is visually distinct from the runner's own
     * messages. Falls back to a plain print when colors are disabled.
     */
    private void printAgentChunk(String text) {
        if (!Ansi.enabled()) {
            System.out.print(text);
            agentLineStart = text.endsWith("\n");
            return;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (agentLineStart) {
                out.append(GUTTER);
                agentLineStart = false;
            }
            out.append(c);
            if (c == '\n') agentLineStart = true;
        }
        System.out.print(out);
    }

    /** Ensure the next runner-emitted line starts on a fresh row, not after a gutter. */
    private void closeAgentGutter() {
        if (!agentLineStart) {
            System.out.println();
            agentLineStart = true;
        }
    }

    private AcpSyncClient buildClient(StdioAcpClientTransport transport) {
        return AcpClient.sync(transport)
            .requestTimeout(Duration.ofMinutes(config.sessionTimeoutMin()))

            // Stream every update; buffer the text chunks for the summary log
            .sessionUpdateConsumer(notification -> {
                var update = notification.update();
                if (update instanceof AgentMessageChunk msg) {
                    stopActiveSpinner();
                    String text = ((TextContent) msg.content()).text();
                    printAgentChunk(text);
                    summaryBuf.get().append(text);
                } else if (update instanceof AgentThoughtChunk thought
                        && config.logThoughts()) {
                    stopActiveSpinner();
                    closeAgentGutter();
                    System.out.println(Ansi.dim("[thinking] "
                        + ((TextContent) thought.content()).text().trim()));
                } else if (update instanceof ToolCall tc
                        && config.logToolCalls()) {
                    stopActiveSpinner();
                    closeAgentGutter();
                    System.out.println(Ansi.dim("[tool:" + tc.kind() + "] " + tc.title()));
                } else if (update instanceof ToolCallUpdateNotification tcu
                        && config.logToolCalls()) {
                    stopActiveSpinner();
                    closeAgentGutter();
                    System.out.println(Ansi.dim(
                        "[tool:" + tcu.toolCallId() + "] → " + tcu.status()));
                }
            })

            // Serve any file the agent wants to read
            .readTextFileHandler(req -> {
                if (config.logToolCalls()) {
                    stopActiveSpinner();
                    closeAgentGutter();
                    System.out.println(Ansi.dim("[read ] " + req.path()));
                }
                try {
                    return new ReadTextFileResponse(Files.readString(Path.of(req.path())));
                } catch (IOException e) {
                    throw new RuntimeException(
                        "Cannot read file: " + req.path() + " — " + e.getMessage(), e);
                }
            })

            // Write any file the agent produces
            .writeTextFileHandler(req -> {
                if (config.logToolCalls()) {
                    stopActiveSpinner();
                    closeAgentGutter();
                    System.out.println(Ansi.dim("[write] " + req.path()
                        + " (" + req.content().length() + " chars)"));
                }
                try {
                    Path p = Path.of(req.path());
                    if (p.getParent() != null) Files.createDirectories(p.getParent());
                    Files.writeString(p, req.content());
                    return new WriteTextFileResponse();
                } catch (IOException e) {
                    throw new RuntimeException(
                        "Cannot write file: " + req.path() + " — " + e.getMessage(), e);
                }
            })

            // Auto-approve every permission request — prefer ALLOW_ALWAYS
            .requestPermissionHandler(req -> {
                String tool = req.toolCall() != null ? req.toolCall().title() : "unknown";
                stopActiveSpinner();
                closeAgentGutter();
                System.out.println(Ansi.dim("[perm ] auto-approved: " + tool));
                List<PermissionOption> opts = req.options();
                String chosen = opts.stream()
                    .filter(o -> o.kind() != null
                        && o.kind().name().contains("ALLOW_ALWAYS"))
                    .map(PermissionOption::optionId)
                    .findFirst()
                    .orElse(opts.isEmpty() ? null : opts.get(0).optionId());
                return chosen != null
                    ? new RequestPermissionResponse(new PermissionSelected(chosen))
                    : new RequestPermissionResponse(new PermissionCancelled());
            })

            .build();
    }

    /**
     * If the user pinned a model, find a matching id among the agent's
     * available models and switch the session to it. Matching is permissive
     * so short names like "sonnet" resolve to "claude-sonnet-4-6".
     */
    private void selectModel(AcpSyncClient client, String sessionId,
                             SessionModelState modelState) {
        if (config.model() == null || config.model().isBlank()) return;
        if (modelState == null || modelState.availableModels() == null
                || modelState.availableModels().isEmpty()) {
            System.out.println("[model] agent did not advertise any models — "
                + "ignoring requested model: " + config.model());
            return;
        }
        String want = config.model().trim();
        ModelInfo match = findModel(modelState.availableModels(), want);
        if (match == null) {
            System.out.println("[model] no match for '" + want + "'. Available: "
                + modelState.availableModels().stream()
                    .map(TaskRunner::modelLabel).toList());
            return;
        }
        if (match.modelId().equals(modelState.currentModelId())) {
            System.out.println("[model] using " + modelLabel(match) + " (already active)");
            return;
        }
        client.setSessionModel(new SetSessionModelRequest(sessionId, match.modelId()));
        System.out.println("[model] switched to " + modelLabel(match));
    }

    /** Render "{id} ({name})" — or just the id if no human name is available. */
    static String modelLabel(ModelInfo m) {
        String id   = m.modelId();
        String name = m.name();
        if (name == null || name.isBlank() || name.equalsIgnoreCase(id)) return id;
        return id + " (" + name + ")";
    }

    /**
     * Resolve a user-supplied model token against the agent's advertised list.
     * Priority: exact id → exact name → partial match (most specific id wins).
     */
    static ModelInfo findModel(List<ModelInfo> models, String want) {
        String w = want.toLowerCase().trim();
        for (ModelInfo m : models) {
            if (m.modelId().equalsIgnoreCase(want)) return m;
        }
        for (ModelInfo m : models) {
            if (m.name() != null && m.name().equalsIgnoreCase(want)) return m;
        }
        ModelInfo best = null;
        for (ModelInfo m : models) {
            String id   = m.modelId().toLowerCase();
            String name = m.name() == null ? "" : m.name().toLowerCase();
            if (id.contains(w) || name.contains(w)) {
                if (best == null || m.modelId().length() > best.modelId().length()) {
                    best = m;
                }
            }
        }
        return best;
    }

    /**
     * Features mode is active when {@code features_dir} points to an existing
     * directory that contains at least one feature subdirectory with a
     * {@code tasks/} child. Otherwise we fall back to the legacy single-folder
     * {@code tasks_dir} layout.
     */
    boolean featuresMode() {
        String fd = config.featuresDir();
        if (fd == null || fd.isBlank()) return false;
        Path dir = Path.of(fd);
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> Files.isDirectory(p) && Files.isDirectory(p.resolve("tasks")));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * One batch per feature when in features mode; otherwise a single
     * unnamed batch from {@code tasks_dir}.
     */
    List<Batch> resolveBatches() throws IOException {
        if (featuresMode()) return resolveFeatureBatches();
        return List.of(new Batch("", resolveTaskPaths()));
    }

    private List<Batch> resolveFeatureBatches() throws IOException {
        Path featuresDir = Path.of(config.featuresDir());
        Comparator<Path> order = "none".equalsIgnoreCase(config.taskSort())
            ? Comparator.comparing(p -> 0)
            : Comparator.comparing(p -> p.getFileName().toString());

        List<Batch> batches = new ArrayList<>();
        try (var stream = Files.list(featuresDir)) {
            List<Path> featureDirs = stream
                .filter(Files::isDirectory)
                .filter(p -> Files.isDirectory(p.resolve("tasks")))
                .sorted(order)
                .toList();
            for (Path featureDir : featureDirs) {
                List<Path> taskPaths = resolveTaskPathsIn(featureDir.resolve("tasks"));
                if (taskPaths.isEmpty()) continue;
                batches.add(new Batch(featureDir.getFileName().toString(), taskPaths));
            }
        }
        return batches;
    }

    /** Returns sorted task paths from {@code config.tasksDir()}. */
    List<Path> resolveTaskPaths() throws IOException {
        Path dir = Path.of(config.tasksDir());
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException(
                "Tasks directory not found: " + dir.toAbsolutePath());
        }
        return resolveTaskPathsIn(dir);
    }

    private List<Path> resolveTaskPathsIn(Path dir) throws IOException {
        Comparator<Path> order = "none".equalsIgnoreCase(config.taskSort())
            ? Comparator.comparing(p -> 0)
            : Comparator.comparing(p -> p.getFileName().toString());

        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> {
                    String name = p.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    String ext = dot >= 0 ? name.substring(dot + 1) : "";
                    return config.taskExtensions().contains(ext);
                })
                .sorted(order)
                .toList();
        }
    }
}
