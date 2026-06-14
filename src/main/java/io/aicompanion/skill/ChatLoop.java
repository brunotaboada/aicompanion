package io.aicompanion.skill;

import io.aicompanion.AgentConsole;
import io.aicompanion.console.Ansi;
import io.aicompanion.util.TokenEstimator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Interactive chat between the user and a local agent, driving a skill to
 * completion. The first turn ships the rendered SKILL.md as the opening
 * prompt; every subsequent turn is the user's typed line shipped back over
 * the same ACP session.
 *
 * <p>Exits when the agent writes the skill's declared output file (the file
 * arrives via the existing {@code writeTextFileHandler} in
 * {@code AcpClientFactory} — this loop just polls {@code Files.exists} after
 * each agent turn, no extra hook needed). The user can also exit explicitly
 * with {@code /abort} or force completion with {@code /done}.
 *
 * <p>Sentinels intercepted before the prompt reaches the agent:
 * <ul>
 *   <li>{@code /abort} → return {@link Outcome#ABORTED}, no agent call</li>
 *   <li>{@code /done}  → ask the agent to wrap up, then return</li>
 *   <li>{@code /skip}  → relay as "no preference, pick a default"</li>
 *   <li>{@code /edit}  → open {@code $EDITOR}; ship the file contents</li>
 * </ul>
 */
public final class ChatLoop {

    /**
     * Why the loop exited. {@code SkillRunner} uses this to decide what to
     * print and what to record in the transcript.
     */
    public enum Outcome {
        /** Output file was written — the skill produced its artifact. */
        COMPLETED,
        /** User typed {@code /abort} or sent EOF. No output file. */
        ABORTED,
        /** User typed {@code /done} — the agent was asked to wrap up but may not have written the file. */
        DONE_FORCED
    }

    /** Functional handle to {@code AcpSyncClient.prompt(sessionId, ...)}. */
    @FunctionalInterface
    public interface PromptSender {
        /** Send a prompt to the agent; block until the agent's turn ends. */
        void send(String prompt);
    }

    private static final String PROMPT = "you> ";

    private final PromptSender    sender;
    private final UserInput       userInput;
    private final EditorLauncher  editor;
    private final AgentConsole    console;
    private final Path            outputFile;
    private final Transcript      transcript;
    private final Consumer<Long>  tokenObserver;

    public ChatLoop(PromptSender sender,
                    UserInput userInput,
                    EditorLauncher editor,
                    AgentConsole console,
                    Path outputFile,
                    Transcript transcript,
                    Consumer<Long> tokenObserver) {
        this.sender        = sender;
        this.userInput     = userInput;
        this.editor        = editor;
        this.console       = console;
        this.outputFile    = outputFile;
        this.transcript    = transcript;
        this.tokenObserver = tokenObserver != null ? tokenObserver : t -> {};
    }

    /**
     * Drive the chat. {@code openingPrompt} is the rendered SKILL.md body —
     * shipped as turn 1 to bootstrap the agent.
     */
    public Outcome run(String openingPrompt) {
        long running = 0;

        running += send(openingPrompt, /*recordUser=*/false, /*systemPrompt=*/true);
        if (outputAppeared()) return finish(Outcome.COMPLETED, running);

        while (true) {
            printTokenCounter(running);
            String line = userInput.readLine(PROMPT);
            if (line == null) return finish(Outcome.ABORTED, running);
            // JLine echoed the line at full terminal width; rewrap it so it
            // stays inside the same right margin as the agent frame.
            if (console != null) console.reframeUserEcho(PROMPT, line);

            String trimmed = line.trim();

            // A bare Enter yields an empty line. Shipping "" to the agent
            // produces an empty text block, which the Claude API rejects with
            // "cache_control cannot be set for empty text blocks". Re-prompt.
            if (trimmed.isEmpty()) {
                continue;
            }

            if ("/abort".equals(trimmed)) {
                return finish(Outcome.ABORTED, running);
            }
            if ("/done".equals(trimmed)) {
                running += send(doneWrapUpPrompt(), /*recordUser=*/false, /*systemPrompt=*/false);
                return finish(outputAppeared() ? Outcome.COMPLETED : Outcome.DONE_FORCED, running);
            }

            String toSend;
            if ("/skip".equals(trimmed)) {
                toSend = "I do not have a strong preference for this question. "
                       + "Pick a reasonable default, note the choice as an open question, and continue.";
            } else if ("/edit".equals(trimmed)) {
                String edited;
                try {
                    edited = editor.openAndRead();
                } catch (IOException | InterruptedException e) {
                    System.err.println(Ansi.yellow("[edit] " + e.getMessage() + " — try again."));
                    continue;
                }
                if (edited == null || edited.isBlank()) {
                    System.out.println(Ansi.dim("[edit] empty — nothing sent, try again."));
                    continue;
                }
                toSend = edited;
            } else {
                toSend = line;
            }

            running += send(toSend, /*recordUser=*/true, /*systemPrompt=*/false);
            if (outputAppeared()) return finish(Outcome.COMPLETED, running);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Send a prompt and return the estimated token cost (input + output).
     * Records to the transcript and resets the console summary buffer.
     */
    private long send(String prompt, boolean recordUser, boolean systemPrompt) {
        if (transcript != null) {
            if (systemPrompt) transcript.recordOpeningPrompt(prompt);
            else if (recordUser) transcript.recordUser(prompt);
        }

        if (console != null) console.resetSummary();
        sender.send(prompt);

        String agentReply = console != null ? console.getSummaryText() : "";
        if (transcript != null) transcript.recordAgent(agentReply);

        long cost = TokenEstimator.estimate(prompt) + TokenEstimator.estimate(agentReply);
        tokenObserver.accept(cost);
        return cost;
    }

    private boolean outputAppeared() {
        return outputFile != null && Files.exists(outputFile);
    }

    private Outcome finish(Outcome outcome, long running) {
        if (transcript != null) transcript.recordOutcome(outcome.name().toLowerCase());
        printTokenCounter(running);
        return outcome;
    }

    private void printTokenCounter(long running) {
        if (running <= 0) return;
        if (console != null) console.closeAgentGutter();
        System.out.println(Ansi.dim("[~" + formatTokens(running) + " tokens]"));
    }

    static String formatTokens(long n) {
        if (n < 1000) return String.valueOf(n);
        double k = n / 1000.0;
        return String.format("%.1fk", k);
    }

    private static String doneWrapUpPrompt() {
        return "The user typed `/done` — wrap up now. "
            + "If you have enough context to produce the output, generate the complete document "
            + "and write it to the expected output file. Otherwise stop with a short summary "
            + "of what is still needed.";
    }
}
