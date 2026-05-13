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
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
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
import io.aicompanion.util.TokenEstimator;
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

    /** Current ACP session — mutates when {@code compact_after_n_tasks} triggers a refresh. */
    private String currentSessionId;

    /** Number of tasks already shipped through {@link #currentSessionId}; used by compact logic. */
    private int tasksInCurrentSession = 0;

    /** Names of tasks completed since the last compaction — handed off to the next session. */
    private final List<String> recentTaskNames = new ArrayList<>();

    /** Running totals across the whole run (estimated). */
    private long inputTokens  = 0;
    private long outputTokens = 0;

    public TaskRunner(Config config) {
        this(config, RunOptions.defaults());
    }

    public TaskRunner(Config config, RunOptions runOptions) {
        this.config     = config;
        this.runOptions = runOptions;
    }

    /**
     * One feature's worth of tasks. {@code featureName} is the subdirectory
     * under {@code features_dir} and prefixes state keys so resume tracks
     * "feature-a/01-task.md" distinctly from "feature-b/01-task.md".
     */
    record Batch(String featureName, List<Path> taskPaths) {
        String stateKeyPrefix() { return featureName + "/"; }
    }

    public void run() throws Exception {
        List<Batch> batches = resolveBatches();
        int totalTasks = batches.stream().mapToInt(b -> b.taskPaths().size()).sum();

        if (totalTasks == 0) {
            System.out.println("No features with tasks found under: " + config.featuresDir());
            return;
        }

        // Dry-run: estimate tokens without resolving an agent or spawning ACP.
        // Skipping AgentRegistry.resolve() here is what makes dry-run useful on
        // machines that don't have an agent installed (eg. CI sizing checks).
        if (runOptions.dryRunTokens()) {
            dryRunTokens(batches, totalTasks);
            return;
        }

        AgentSpec spec    = AgentRegistry.resolve(config);
        Path      projDir = Path.of(config.projectDir()).toAbsolutePath();
        var       verifier = new TestVerifier(config.testCommand(), projDir);
        var       reporter = new Reporter(config);

        if (runOptions.fresh()) {
            try { RunState.delete(); } catch (IOException ignore) {}
        }
        RunState state = RunState.load();
        state.setFeaturesDir(config.featuresDir());
        int wouldSkip = announceResume(state, batches);

        System.out.println("Agent   : " + spec.id());
        System.out.println("Features: " + batches.size()
            + "  (" + totalTasks + " task" + (totalTasks == 1 ? "" : "s") + ")");
        System.out.println("Dir     : " + projDir);
        if (config.maxTokensPerRun() > 0) {
            System.out.println("Budget  : " + config.maxTokensPerRun() + " tokens (estimated)");
        }
        System.out.println();

        // Nothing to actually run? Skip the agent spawn entirely. Spinning up
        // ACP just to print "skipped" lines is wasteful and (when the agent
        // can't connect) produces an alarming stack trace for a no-op run.
        if (wouldSkip == totalTasks) {
            System.out.println(Ansi.green("Nothing to do — every task is already passed."));
            System.out.println(Ansi.dim("Use --fresh to wipe state and re-run, "
                + "or --retry-failed to re-run only failures."));
            return;
        }

        var transport = new StdioAcpClientTransport(spec.params(config).get());

        try (AcpSyncClient client = buildClient(transport)) {

            client.initialize(new InitializeRequest(1,
                new ClientCapabilities(new FileSystemCapability(true, true), false)));

            var session = client.newSession(
                new NewSessionRequest(projDir.toString(), List.of()));
            currentSessionId      = session.sessionId();
            tasksInCurrentSession = 0;
            recentTaskNames.clear();
            System.out.println("Session: " + currentSessionId);
            selectModel(client, currentSessionId, session.models());
            if (config.initInstructions()) {
                sendInitInstructions(client);
            }
            System.out.println();

            int taskOffset = 0;
            for (Batch batch : batches) {
                if (Thread.currentThread().isInterrupted()) {
                    System.out.println(Ansi.yellow("Aborted by user."));
                    return;
                }
                System.out.println(Ansi.bold(Ansi.cyan(
                    "═══ Feature: " + batch.featureName()
                        + "  (" + batch.taskPaths().size() + " task"
                        + (batch.taskPaths().size() == 1 ? "" : "s") + ") ═══")));
                boolean keepGoing = runTaskBatch(client, projDir, state,
                    batch, taskOffset, totalTasks, verifier, reporter);
                taskOffset += batch.taskPaths().size();
                if (!keepGoing) return;
            }

            System.out.println(Ansi.dim("─".repeat(60)));
            System.out.println(Ansi.green("All " + totalTasks + " tasks complete."));
            printTokenSummary();
        } finally {
            stopActiveSpinner();
        }
    }

    /**
     * Run all tasks in one batch through an existing ACP session.
     *
     * @param taskOffset display offset so the "Task N/total" counter remains
     *                   global across features
     * @return false when stop-on-failure (or the token budget) was triggered
     */
    private boolean runTaskBatch(AcpSyncClient client, Path projDir,
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
            String displayName = prefix + taskName;

            String taskContent = Files.readString(taskPath);
            String taskHash    = RunState.hash(taskContent);

            if (state.shouldSkip(stateKey, taskHash, runOptions.retryFailed())) {
                System.out.printf("%s %d/%d: %s %s%n",
                    Ansi.bold("Task"), globalIdx, totalTasks, Ansi.cyan(displayName),
                    Ansi.green("✓ skipped (already "
                        + state.get(stateKey).status().name().toLowerCase() + ")"));
                continue;
            }

            // Pre-check: if tests already pass for this task, skip the agent
            // round trip entirely. Only attempt when verification is on and a
            // test command is configured — otherwise there is nothing to check.
            if (config.preCheckTests() && config.testEnabled()
                    && config.testCommand() != null && !config.testCommand().isBlank()) {
                System.out.println(Ansi.dim("─".repeat(60)));
                System.out.printf("%s %d/%d: %s %s%n",
                    Ansi.bold("Task"), globalIdx, totalTasks, Ansi.cyan(displayName),
                    Ansi.dim("(pre-check)"));
                TestVerifier.Result pre = runTestsWithSpinner(verifier, "Pre-check tests");
                if (pre.passed()) {
                    System.out.println(Ansi.green(
                        "✓ Tests already pass — skipping agent call.\n"));
                    state.markPassed(stateKey, taskHash);
                    persistState(state);
                    continue;
                }
                System.out.println(Ansi.dim(
                    "Pre-check found failures; proceeding with agent."));
            }

            // Compact: refresh the session every N tasks to bound context drift.
            maybeCompactSession(client, projDir);

            System.out.println(Ansi.dim("─".repeat(60)));
            System.out.printf("%s %d/%d: %s%n",
                Ansi.bold("Task"), globalIdx, totalTasks, Ansi.cyan(displayName));
            System.out.println(Ansi.dim("─".repeat(60)));

            summaryBuf.set(new StringBuilder());

            String taskBody = config.taskPreambleStrip()
                ? Prompts.stripPreamble(taskContent)
                : taskContent;
            String prompt = config.initInstructions()
                ? Prompts.forTaskShort(taskBody)
                : Prompts.forTask(taskBody);

            agentLineStart = true;
            Spinner thinking = new Spinner("agent thinking");
            activeSpinner.set(thinking);
            thinking.start();

            inputTokens += TokenEstimator.estimate(prompt);
            var response = client.prompt(new PromptRequest(
                currentSessionId, List.of(new TextContent(prompt))));

            stopActiveSpinner();
            closeAgentGutter();
            System.out.println(Ansi.dim("[stop] " + response.stopReason()));
            outputTokens += TokenEstimator.estimate(summaryBuf.get().toString());

            reporter.write(displayName, summaryBuf.get().toString());

            if (config.testEnabled()) {
                TestVerifier.Result result = runTestsWithSpinner(verifier, "Running tests");

                int attempt = 0;
                while (!result.passed() && attempt < config.maxFixAttempts()) {
                    attempt++;
                    System.out.printf("%s Tests FAILED — fix attempt %d/%d%n",
                        Ansi.red("✗"), attempt, config.maxFixAttempts());
                    System.out.println(result.output());

                    String fixPrompt = config.initInstructions()
                        ? Prompts.forFixShort(config.testCommand(), result.output(),
                            config.fixOutputMaxLines())
                        : Prompts.forFix(config.testCommand(), result.output(),
                            config.fixOutputMaxLines());

                    summaryBuf.set(new StringBuilder());
                    agentLineStart = true;
                    Spinner fixThinking = new Spinner("agent thinking (fix attempt " + attempt + ")");
                    activeSpinner.set(fixThinking);
                    fixThinking.start();

                    inputTokens += TokenEstimator.estimate(fixPrompt);
                    var fixResp = client.prompt(new PromptRequest(
                        currentSessionId, List.of(new TextContent(fixPrompt))));

                    stopActiveSpinner();
                    closeAgentGutter();
                    System.out.println(Ansi.dim("[stop] " + fixResp.stopReason()));
                    outputTokens += TokenEstimator.estimate(summaryBuf.get().toString());

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

            tasksInCurrentSession++;
            recentTaskNames.add(displayName);

            if (budgetExceeded()) {
                System.out.println(Ansi.yellow(
                    "Stopping — estimated token budget exceeded ("
                        + (inputTokens + outputTokens) + " / "
                        + config.maxTokensPerRun() + ")."));
                printTokenSummary();
                return false;
            }
        }
        return true;
    }

    /**
     * Send a single fire-and-forget instruction prompt at session start so the
     * agent receives the summary-format rules once instead of on every task.
     * Subsequent task prompts use {@link Prompts#forTaskShort(String)} which
     * just back-references "the established format".
     */
    private void sendInitInstructions(AcpSyncClient client) {
        String prompt = Prompts.forSessionInit();
        summaryBuf.set(new StringBuilder());
        agentLineStart = true;
        Spinner s = new Spinner("session init");
        activeSpinner.set(s);
        s.start();
        try {
            inputTokens += TokenEstimator.estimate(prompt);
            client.prompt(new PromptRequest(
                currentSessionId, List.of(new TextContent(prompt))));
        } finally {
            stopActiveSpinner();
            closeAgentGutter();
            outputTokens += TokenEstimator.estimate(summaryBuf.get().toString());
        }
        System.out.println(Ansi.dim("[init] session instructions sent"));
    }

    /**
     * If {@code compact_after_n_tasks} is set and the current session has
     * processed that many tasks, end it and start a fresh one. A short
     * "previously completed" handoff is sent so the agent has context without
     * inheriting the full transcript.
     */
    private void maybeCompactSession(AcpSyncClient client, Path projDir) {
        int threshold = config.compactAfterNTasks();
        if (threshold <= 0 || tasksInCurrentSession < threshold) return;
        try {
            NewSessionResponse fresh = client.newSession(
                new NewSessionRequest(projDir.toString(), List.of()));
            currentSessionId = fresh.sessionId();
            System.out.println(Ansi.dim("[compact] fresh session: " + currentSessionId
                + " (after " + tasksInCurrentSession + " tasks)"));
            selectModel(client, currentSessionId, fresh.models());
            if (config.initInstructions()) sendInitInstructions(client);

            String handoff = Prompts.forCompactHandoff(List.copyOf(recentTaskNames));
            summaryBuf.set(new StringBuilder());
            agentLineStart = true;
            inputTokens += TokenEstimator.estimate(handoff);
            client.prompt(new PromptRequest(
                currentSessionId, List.of(new TextContent(handoff))));
            closeAgentGutter();
            outputTokens += TokenEstimator.estimate(summaryBuf.get().toString());

            tasksInCurrentSession = 0;
            recentTaskNames.clear();
        } catch (RuntimeException e) {
            System.err.println(Ansi.yellow(
                "Warning: could not compact session — continuing with current one. ("
                    + e.getMessage() + ")"));
        }
    }

    private boolean budgetExceeded() {
        int budget = config.maxTokensPerRun();
        if (budget <= 0) return false;
        return (inputTokens + outputTokens) >= budget;
    }

    /**
     * Compute prompts that {@code run} would have sent and print estimated
     * token totals — no agent is spawned, no tests are run. Useful for tuning
     * {@code task_preamble_strip}, {@code init_instructions}, etc.
     */
    private void dryRunTokens(List<Batch> batches, int totalTasks) throws IOException {
        long inEstimate = 0;
        if (config.initInstructions()) {
            inEstimate += TokenEstimator.estimate(Prompts.forSessionInit());
        }
        System.out.println(Ansi.bold("Dry-run token estimate"));
        System.out.println(Ansi.dim("─".repeat(60)));
        int n = 0;
        for (Batch batch : batches) {
            for (Path taskPath : batch.taskPaths()) {
                n++;
                String body = Files.readString(taskPath);
                if (config.taskPreambleStrip()) body = Prompts.stripPreamble(body);
                String prompt = config.initInstructions()
                    ? Prompts.forTaskShort(body)
                    : Prompts.forTask(body);
                int t = TokenEstimator.estimate(prompt);
                inEstimate += t;
                System.out.printf("  %3d. %-40s %s%n",
                    n,
                    batch.stateKeyPrefix() + taskPath.getFileName(),
                    Ansi.dim("~" + t + " tok"));
            }
        }
        System.out.println(Ansi.dim("─".repeat(60)));
        System.out.printf("  Tasks:              %d%n", totalTasks);
        System.out.printf("  Estimated input:    ~%d tokens (initial prompts only)%n", inEstimate);
        if (config.testEnabled() && config.maxFixAttempts() > 0) {
            System.out.println(Ansi.dim(
                "  (Fix-loop prompts add variable tokens per failed test; not counted.)"));
        }
        if (config.maxTokensPerRun() > 0) {
            System.out.printf("  Configured budget:  %d tokens%n", config.maxTokensPerRun());
        }
        System.out.println(Ansi.dim(
            "Token counts are estimates (~4 chars/token). No agent was invoked."));
    }

    private void printTokenSummary() {
        long total = inputTokens + outputTokens;
        System.out.printf("%s ~%d in, ~%d out, ~%d total%n",
            Ansi.dim("[tokens]"), inputTokens, outputTokens, total);
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
     *
     * @return number of tasks that would be skipped on this run; the caller
     *         uses this to short-circuit the agent spawn when every task is
     *         already done
     */
    private int announceResume(RunState state, List<Batch> batches) {
        if (state.tasks().isEmpty()) return 0;

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
        if (totalSkip == 0) return 0;

        StringBuilder msg = new StringBuilder("[resume] ");
        msg.append(passedSkip).append(" passed");
        if (failedSkip > 0) msg.append(", ").append(failedSkip).append(" failed");
        msg.append(" — skipping ").append(totalSkip).append('/').append(total);
        msg.append(". Use --fresh");
        if (failedSkip > 0) msg.append(" or --retry-failed");
        msg.append(" to re-run.");
        System.out.println(Ansi.dim(msg.toString()));
        return totalSkip;
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
     * One batch per feature subdirectory under {@code features_dir} that has
     * a {@code tasks/} child with at least one task file. Sorted by feature
     * name (or insertion order when {@code task_sort=none}).
     *
     * Whether {@code features/} contains one feature or many, the runner
     * iterates all of them — there is no special single-feature short-circuit.
     */
    List<Batch> resolveBatches() throws IOException {
        Path featuresDir = Path.of(config.featuresDir());
        if (!Files.isDirectory(featuresDir)) {
            throw new IllegalArgumentException(
                "Features directory not found: " + featuresDir.toAbsolutePath()
                + " — create it with at least one feature subfolder containing tasks/.");
        }

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
