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

    public TaskRunner(Config config) {
        this(config, RunOptions.defaults(), null);
    }

    public TaskRunner(Config config, RunOptions runOptions) {
        this(config, runOptions, null);
    }

    public TaskRunner(Config config, RunOptions runOptions, Terminal terminal) {
        this.config        = config;
        this.runOptions    = runOptions;
        this.console       = new AgentConsole(terminal);
        this.batchResolver = new BatchResolver(config);
    }

    public void run() throws Exception {
        List<Batch> batches   = batchResolver.resolveBatches();
        int         totalTasks = batches.stream().mapToInt(b -> b.taskPaths().size()).sum();

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
        var       verifier = new TestVerifier(config.testCommand(), projDir, config.testTimeoutMin());
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

            var session = client.newSession(new NewSessionRequest(projDir.toString(), List.of()));
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
                boolean keepGoing = runTaskBatch(
                    client, projDir, state, batch, taskOffset, totalTasks, verifier, reporter);
                taskOffset += batch.taskPaths().size();
                if (!keepGoing) return;
            }

            System.out.println(Ansi.rule());
            System.out.println(Ansi.green("All " + totalTasks + " tasks complete."));
            printTokenSummary();
        } finally {
            console.stopActiveSpinner();
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
                System.out.println(Ansi.rule());
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
            state.markPassed(stateKey, taskHash);
            return true;
        }

        TestVerifier.Result result = runTestsWithSpinner(verifier, "Running tests");
        int attempt = 0;
        while (!result.passed() && attempt < config.maxFixAttempts()) {
            attempt++;
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
            reporter.write(displayName + ".fix" + attempt, console.getSummaryText());

            result = runTestsWithSpinner(verifier, "Re-running tests");
        }

        if (result.passed()) {
            System.out.println(attempt == 0
                ? Ansi.green("✓ Tests passed") + "\n"
                : Ansi.green("✓ Tests passed") + " after " + attempt + " fix attempt(s)\n");
            state.markPassed(stateKey, taskHash);
            return true;
        } else {
            System.out.printf("%s Tests still FAILED after %d fix attempt(s)%n",
                Ansi.red("✗"), config.maxFixAttempts());
            System.out.println(result.output());
            state.markFailed(stateKey, taskHash);
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
            // The fresh session's usage reports restart at zero — bank the old
            // session's reported figures so run totals stay cumulative.
            stats.rolloverSession();
            currentSessionId = fresh.sessionId();
            System.out.println(Ansi.dim("[compact] fresh session: " + currentSessionId
                + " (after " + tasksInCurrentSession + " tasks)"));
            selectModel(client, currentSessionId, fresh.models());
            if (config.initInstructions()) sendInitInstructions(client);

            sendPrompt(client, null, Prompts.forCompactHandoff(List.copyOf(recentTaskNames)));

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
        } finally {
            if (spinnerLabel != null) {
                if (console.hasToggle()) console.stopToggle();
                else console.stopActiveSpinner();
            }
            console.closeAgentGutter();
            outputTokens += TokenEstimator.estimate(console.getSummaryText());
        }
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
            System.out.println("[model] using " + AgentRegistry.modelLabel(match) + " (already active)");
            return;
        }
        client.setSessionModel(new SetSessionModelRequest(sessionId, match.modelId()));
        System.out.println("[model] switched to " + AgentRegistry.modelLabel(match));
    }
}
