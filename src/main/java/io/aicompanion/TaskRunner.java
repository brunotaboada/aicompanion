package io.aicompanion;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ModelInfo;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionModelState;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModelRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import io.aicompanion.agent.AcpClientFactory;
import io.aicompanion.agent.AgentRegistry;
import io.aicompanion.agent.AgentSpec;
import io.aicompanion.agent.SessionStats;
import io.aicompanion.config.Config;
import io.aicompanion.console.Ansi;
import io.aicompanion.console.StatusBar;
import io.aicompanion.util.TokenEstimator;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.jline.terminal.Terminal;

public class TaskRunner {

    private final Config         config;
    private final RunOptions     runOptions;
    private final AgentConsole   console;
    private final BatchResolver  batchResolver;

    /** Current ACP session — mutates when {@code compact_after_n_tasks} triggers a refresh. */
    private String currentSessionId;

    /** Number of tasks shipped through {@link #currentSessionId}; used by compact logic. */
    private int tasksInCurrentSession = 0;

    /** Names of tasks completed since the last compaction — handed off to the next session. */
    private final List<String> recentTaskNames = new ArrayList<>();

    /** Running totals across the whole run (estimated fallback). */
    private long inputTokens  = 0;
    private long outputTokens = 0;

    /** Agent-reported usage and touched files, fed by the ACP consumer thread. */
    private final SessionStats stats = new SessionStats();

    /** Bottom-row live status (agent/model/task/tests/fix); no-op without a terminal. */
    private final StatusBar status;

    /** Active transport — closed on user interrupt to unblock in-flight prompts. */
    private StdioAcpClientTransport activeTransport;

    /** Tasks that failed agent or test verification this run. */
    private int tasksFailedThisRun;

    public TaskRunner(Config config) {
        this(config, RunOptions.defaults(), null);
    }

    public TaskRunner(Config config, RunOptions runOptions) {
        this(config, runOptions, null);
    }

    public TaskRunner(Config config, RunOptions runOptions, Terminal terminal) {
        this(config, runOptions, terminal, null);
    }

    public TaskRunner(Config config, RunOptions runOptions, Terminal terminal,
                      StatusBar statusBar) {
        this.config        = config;
        this.runOptions    = runOptions;
        this.console       = new AgentConsole(terminal);
        this.batchResolver = new BatchResolver(config);
        this.status        = statusBar != null ? statusBar : StatusBar.attach(null);
    }

    public RunResult run() throws Exception {
        List<Batch> batches   = batchResolver.resolveBatches();
        int         totalTasks = batches.stream().mapToInt(b -> b.taskPaths().size()).sum();

        if (totalTasks == 0) {
            System.out.println("No features with tasks found under: " + config.featuresDir());
            return new RunResult(RunResult.Outcome.NO_TASKS, 0);
        }

        if (runOptions.dryRunTokens()) {
            dryRunTokens(batches, totalTasks);
            return RunResult.success();
        }

        try (RunStateLock ignored = RunStateLock.acquire()) {
            return runLocked(batches, totalTasks);
        }
    }

    private RunResult runLocked(List<Batch> batches, int totalTasks) throws Exception {
        tasksFailedThisRun = 0;

        AgentSpec spec    = AgentRegistry.resolve(config);
        Path      projDir = Path.of(config.projectDir()).toAbsolutePath();
        var       verifier = new TestVerifier(config.testCommand(), projDir, config.testTimeoutMin());
        var       reporter = new Reporter(config);

        if (runOptions.fresh()) {
            try { RunState.delete(); } catch (IOException ignore) {}
        }
        RunState state = RunState.load();
        if (state.loadWarning() != null) {
            System.out.println(Ansi.yellow(state.loadWarning()));
        }
        if (!state.isResumeValidFor(config.featuresDir())) {
            System.out.println(Ansi.yellow(
                "[resume] features_dir changed (was "
                    + state.storedFeaturesDir() + ", now " + config.featuresDir()
                    + ") — ignoring previous resume state."));
            state.clearTasks();
        }
        state.setFeaturesDir(config.featuresDir());
        int wouldSkip = announceResume(state, batches);

        status.setAgent(spec.id());
        if (config.model() != null && !config.model().isBlank()) {
            status.setModel(config.model());
        }

        System.out.println("Agent   : " + spec.id());
        System.out.println("Features: " + batches.size()
            + "  (" + totalTasks + " task" + (totalTasks == 1 ? "" : "s") + ")");
        System.out.println("Dir     : " + projDir);
        if (config.maxTokensPerRun() > 0) {
            System.out.println("Budget  : " + config.maxTokensPerRun() + " tokens (estimated)");
        }
        System.out.println();

        if (wouldSkip == totalTasks) {
            System.out.println(Ansi.green("Nothing to do — every task is already passed."));
            System.out.println(Ansi.dim("Use --fresh to wipe state and re-run, "
                + "or --retry-failed to re-run only failures."));
            return new RunResult(RunResult.Outcome.NOTHING_TO_DO, 0);
        }

        activeTransport = new StdioAcpClientTransport(spec.params(config).get());

        try (AcpSyncClient client = buildClient(activeTransport)) {

            client.initialize(new InitializeRequest(1,
                new ClientCapabilities(new FileSystemCapability(true, true), false)));

            if (config.reuseSession()) {
                beginSession(client, projDir, /*rollover=*/false);
                System.out.println();
            }

            int taskOffset = 0;
            for (Batch batch : batches) {
                if (Thread.currentThread().isInterrupted()) {
                    System.out.println(Ansi.yellow("Aborted by user."));
                    return new RunResult(RunResult.Outcome.ABORTED, tasksFailedThisRun);
                }
                System.out.println(Ansi.bold(Ansi.cyan(
                    "═══ Feature: " + batch.featureName()
                        + "  (" + batch.taskPaths().size() + " task"
                        + (batch.taskPaths().size() == 1 ? "" : "s") + ") ═══")));
                boolean keepGoing = runTaskBatch(
                    client, projDir, state, batch, taskOffset, totalTasks, verifier, reporter);
                taskOffset += batch.taskPaths().size();
                if (!keepGoing) {
                    if (Thread.currentThread().isInterrupted()) {
                        return new RunResult(RunResult.Outcome.ABORTED, tasksFailedThisRun);
                    }
                    if (budgetExceeded()) {
                        return new RunResult(RunResult.Outcome.BUDGET_EXCEEDED, tasksFailedThisRun);
                    }
                    return new RunResult(RunResult.Outcome.FAILURE, tasksFailedThisRun);
                }
            }

            System.out.println(Ansi.rule());
            System.out.println(Ansi.green("All " + totalTasks + " tasks complete."));
            printTokenSummary();
            return tasksFailedThisRun > 0
                ? new RunResult(RunResult.Outcome.FAILURE, tasksFailedThisRun)
                : RunResult.success();
        } finally {
            console.stopActiveSpinner();
            activeTransport = null;
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

        for (int i = 0; i < taskPaths.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                System.out.println(Ansi.yellow(
                    "Aborted by user — stopped before task " + (taskOffset + i + 1) + "."));
                return false;
            }

            Path   taskPath    = taskPaths.get(i);
            String stateKey    = batch.stateKeyFor(taskPath);
            int    globalIdx   = taskOffset + i + 1;
            String displayName = stateKey;

            String taskContent = Files.readString(taskPath);
            String taskHash    = RunState.hash(taskContent);

            status.setTask(globalIdx, totalTasks, displayName);
            status.clearFix();

            if (state.shouldSkip(stateKey, taskHash, runOptions.retryFailed())) {
                status.setTestsSkipped();
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
                System.out.println(Ansi.rule());
                System.out.printf("%s %d/%d: %s %s%n",
                    Ansi.bold("Task"), globalIdx, totalTasks, Ansi.cyan(displayName),
                    Ansi.dim("(pre-check)"));
                TestVerifier.Result pre = runTestsWithSpinner(verifier, "Pre-check tests");
                if (pre.passed()) {
                    status.setTestsPass();
                    System.out.println(Ansi.green(
                        "✓ Tests already pass — skipping agent call for this run only."));
                    System.out.println(Ansi.dim(
                        "  (pre-check does not mark the task complete — global tests may pass "
                            + "before this task's work is done.)\n"));
                    continue;
                }
                System.out.println(Ansi.dim(
                    "Pre-check found failures; proceeding with agent."));
            }

            // Per-task isolation, or periodic compaction when reusing one session.
            if (!config.reuseSession()) {
                beginSession(client, projDir, currentSessionId != null);
            } else {
                maybeCompactSession(client, projDir);
            }

            System.out.println(Ansi.rule());
            System.out.printf("%s %d/%d: %s%n",
                Ansi.bold("Task"), globalIdx, totalTasks, Ansi.cyan(displayName));
            System.out.println(Ansi.rule());

            // Touched files are tracked per task: fix prompts list what this
            // task's agent turns changed, not leftovers from earlier tasks.
            stats.resetTouched();

            String taskBody = config.taskPreambleStrip()
                ? Prompts.stripPreamble(taskContent)
                : taskContent;
            String prompt = config.initInstructions()
                ? Prompts.forTaskShort(taskBody)
                : Prompts.forTask(taskBody);

            String stopReason = sendPrompt(client, "agent working", prompt);
            System.out.println(Ansi.dim("[stop] " + stopReason));
            if (!isSuccessfulAgentStop(stopReason)) {
                System.out.println(Ansi.red("✗ Agent stopped with: " + stopReason));
                state.markFailed(stateKey, taskHash);
                tasksFailedThisRun++;
                persistState(state);
                return !config.stopOnFailure();
            }
            reporter.write(displayName, console.getSummaryText());

            boolean keepGoing = runVerifyAndFix(
                client, verifier, reporter, state, stateKey, taskHash, displayName);
            persistState(state);
            if (!keepGoing) {
                System.out.println(Ansi.yellow(
                    "Stopping — fix the failure before continuing."));
                return false;
            }

            tasksInCurrentSession++;
            recentTaskNames.add(displayName);

            if (budgetExceeded()) {
                System.out.println(Ansi.yellow(
                    "Stopping — token budget exceeded ("
                        + tokensUsedForBudget() + " / "
                        + config.maxTokensPerRun() + ")."));
                printTokenSummary();
                return false;
            }
        }
        return true;
    }

    /**
     * Run tests after an agent turn. On failure, send fix prompts up to
     * {@code maxFixAttempts} times. Updates state (markPassed / markFailed).
     *
     * @return false when {@code stop_on_failure} is set and tests still fail
     *         after all attempts; the caller should persist state then stop
     */
    private boolean runVerifyAndFix(AcpSyncClient client,
                                    TestVerifier verifier, Reporter reporter,
                                    RunState state,
                                    String stateKey, String taskHash,
                                    String displayName) {
        if (!config.testEnabled()) {
            status.setTestsSkipped();
            state.markPassed(stateKey, taskHash);
            return true;
        }

        TestVerifier.Result result = runTestsWithSpinner(verifier, "Running tests");
        int attempt = 0;
        while (!result.passed() && attempt < config.maxFixAttempts()) {
            attempt++;
            status.setTestsFail();
            status.setFix(attempt, config.maxFixAttempts());
            System.out.printf("%s Tests FAILED — fix attempt %d/%d%n",
                Ansi.red("✗"), attempt, config.maxFixAttempts());
            System.out.println(result.output());

            String fixPrompt = config.initInstructions()
                ? Prompts.forFixShort(config.testCommand(), result.output(),
                    config.fixOutputMaxLines(), stats.touchedFiles())
                : Prompts.forFix(config.testCommand(), result.output(),
                    config.fixOutputMaxLines(), stats.touchedFiles());

            String fixStop = sendPrompt(client,
                "agent working (fix attempt " + attempt + ")", fixPrompt);
            System.out.println(Ansi.dim("[stop] " + fixStop));
            if (!isSuccessfulAgentStop(fixStop)) {
                System.out.println(Ansi.red("✗ Agent stopped during fix: " + fixStop));
                break;
            }
            reporter.write(displayName + ".fix" + attempt, console.getSummaryText());

            result = runTestsWithSpinner(verifier, "Re-running tests");
        }

        if (result.passed()) {
            status.setTestsPass();
            System.out.println(attempt == 0
                ? Ansi.green("✓ Tests passed") + "\n"
                : Ansi.green("✓ Tests passed") + " after " + attempt + " fix attempt(s)\n");
            state.markPassed(stateKey, taskHash);
            return true;
        } else {
            status.setTestsFail();
            System.out.printf("%s Tests still FAILED after %d fix attempt(s)%n",
                Ansi.red("✗"), config.maxFixAttempts());
            System.out.println(result.output());
            state.markFailed(stateKey, taskHash);
            tasksFailedThisRun++;
            return !config.stopOnFailure();
        }
    }

    /**
     * Send a single fire-and-forget instruction prompt at session start so the
     * agent receives the summary-format rules once instead of on every task.
     * Subsequent task prompts use {@link Prompts#forTaskShort(String)} which
     * just back-references "the established format".
     */
    private void sendInitInstructions(AcpSyncClient client) {
        sendPrompt(client, "session init", Prompts.forSessionInit());
        System.out.println(Ansi.dim("[init] session instructions sent"));
    }

    /**
     * Open (or reopen) an ACP session on {@code projDir}. When {@code rollover}
     * is true, bank usage from the previous session before switching.
     */
    private void beginSession(AcpSyncClient client, Path projDir, boolean rollover) {
        beginSession(client, projDir, rollover, true);
    }

    /**
     * @param resetTaskCounter when false, leaves {@link #tasksInCurrentSession}
     *        untouched so compaction can retry if the handoff prompt fails
     */
    private void beginSession(AcpSyncClient client, Path projDir, boolean rollover,
                              boolean resetTaskCounter) {
        if (rollover) stats.rolloverSession();
        NewSessionResponse session = client.newSession(
            new NewSessionRequest(projDir.toString(), List.of()));
        currentSessionId = session.sessionId();
        if (resetTaskCounter) tasksInCurrentSession = 0;
        System.out.println("Session: " + currentSessionId);
        selectModel(client, currentSessionId, session.models());
        if (config.initInstructions()) sendInitInstructions(client);
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
            beginSession(client, projDir, /*rollover=*/true, /*resetTaskCounter=*/false);
            System.out.println(Ansi.dim("[compact] fresh session after "
                + threshold + " task(s)"));
            sendPrompt(client, null, Prompts.forCompactHandoff(List.copyOf(recentTaskNames)));
            recentTaskNames.clear();
            tasksInCurrentSession = 0;
        } catch (RuntimeException e) {
            // Stay at/above threshold so the handoff is retried on the next task.
            if (tasksInCurrentSession < threshold) tasksInCurrentSession = threshold;
            System.err.println(Ansi.yellow(
                "Warning: session compaction failed — will retry on the next task. ("
                    + e.getMessage() + ")"));
        }
    }

    private boolean budgetExceeded() {
        int budget = config.maxTokensPerRun();
        if (budget <= 0) return false;
        return tokensUsedForBudget() >= budget;
    }

    /**
     * Tokens counted against {@code max_tokens_per_run}: the agent's own usage
     * reports when it sends them (exact), otherwise the chars/4 estimate of
     * prompts + streamed summaries.
     */
    private long tokensUsedForBudget() {
        return stats.hasReportedUsage()
            ? stats.totalUsedTokens()
            : inputTokens + outputTokens;
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
        System.out.println(Ansi.rule());
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
        System.out.println(Ansi.rule());
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
        if (stats.hasReportedUsage()) {
            StringBuilder line = new StringBuilder("agent-reported: ")
                .append(stats.totalUsedTokens()).append(" tokens used");
            Double cost = stats.totalCost();
            if (cost != null) {
                line.append(", cost ").append(String.format("%.4f", cost));
                if (stats.costCurrency() != null) line.append(' ').append(stats.costCurrency());
            }
            System.out.println(Ansi.dim("[tokens] ") + line);
            return;
        }
        long total = inputTokens + outputTokens;
        System.out.printf("%s ~%d in, ~%d out, ~%d total (estimated)%n",
            Ansi.dim("[tokens]"), inputTokens, outputTokens, total);
    }

    private TestVerifier.Result runTestsWithSpinner(TestVerifier verifier, String label) {
        status.setTestsRunning();
        console.startSpinner(label);
        try {
            return verifier.run();
        } finally {
            console.stopActiveSpinner();
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

    /**
     * Send a prompt to the current session, tracking tokens both ways.
     * If {@code spinnerLabel} is non-null, a spinner (or the toggle thread when a
     * terminal is present) is started before the call and stopped after.
     *
     * @return the agent's stop reason
     */
    private String sendPrompt(AcpSyncClient client, String spinnerLabel, String prompt) {
        if (Thread.currentThread().isInterrupted()) {
            abortInFlight();
            throw new RuntimeException("Aborted by user");
        }
        console.resetSummary();
        if (spinnerLabel != null) {
            if (console.hasToggle()) {
                console.startToggle();
            } else {
                console.startSpinner(spinnerLabel);
            }
        }
        try {
            inputTokens += TokenEstimator.estimate(prompt);
            var resp = client.prompt(new PromptRequest(
                currentSessionId, List.of(new TextContent(prompt))));
            return String.valueOf(resp.stopReason());
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                abortInFlight();
                throw new RuntimeException("Aborted by user", e);
            }
            throw e;
        } finally {
            if (spinnerLabel != null) {
                if (console.hasToggle()) console.stopToggle();
                else console.stopActiveSpinner();
            }
            console.closeAgentGutter();
            outputTokens += TokenEstimator.estimate(console.getSummaryText());
        }
    }

    private void abortInFlight() {
        StdioAcpClientTransport t = activeTransport;
        if (t != null) {
            try { t.close(); } catch (Exception ignored) {}
        }
    }

    /** Treat only a normal end-of-turn as success before running tests. */
    static boolean isSuccessfulAgentStop(String stopReason) {
        if (stopReason == null || stopReason.isBlank()) return true;
        String r = stopReason.trim().toLowerCase();
        return r.contains("end_turn") || r.contains("end turn") || "stop".equals(r);
    }

    private AcpSyncClient buildClient(StdioAcpClientTransport transport) {
        return AcpClientFactory.build(transport, config, console, stats);
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
        ModelInfo match = AgentRegistry.findModel(modelState.availableModels(), want);
        if (match == null) {
            System.out.println("[model] no match for '" + want + "'. Available: "
                + modelState.availableModels().stream()
                    .map(AgentRegistry::modelLabel).toList());
            return;
        }
        if (match.modelId().equals(modelState.currentModelId())) {
            status.setModel(AgentRegistry.modelLabel(match));
            System.out.println("[model] using " + AgentRegistry.modelLabel(match) + " (already active)");
            return;
        }
        client.setSessionModel(new SetSessionModelRequest(sessionId, match.modelId()));
        status.setModel(AgentRegistry.modelLabel(match));
        System.out.println("[model] switched to " + AgentRegistry.modelLabel(match));
    }
}
