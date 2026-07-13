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
 * {@code AcpClientFactory} — this loop polls after each agent turn).
 * A pre-existing output file does <em>not</em> count as completion; only a
 * file created or modified during this session does. The user can also exit
 * explicitly with {@code /abort} or force completion with {@code /done}.
 */
public final class ChatLoop {

    public enum Outcome {
        COMPLETED,
        ABORTED,
        DONE_FORCED
    }

    @FunctionalInterface
    public interface PromptSender {
        void send(String prompt);
    }

    /** Snapshot of a file at session start — used to detect new writes. */
    record FileBaseline(boolean existed, long lastModified, long size) {}

    private static final String PROMPT = "you> ";

    private final PromptSender    sender;
    private final UserInput       userInput;
    private final EditorLauncher  editor;
    private final AgentConsole    console;
    private final Path            outputFile;
    private final Path            completionFile;
    private final FileBaseline    outputBaseline;
    private final FileBaseline    completionBaseline;
    private final Transcript      transcript;
    private final Consumer<Long>  tokenObserver;

    public ChatLoop(PromptSender sender,
                    UserInput userInput,
                    EditorLauncher editor,
                    AgentConsole console,
                    Path outputFile,
                    Transcript transcript,
                    Consumer<Long> tokenObserver) {
        this(sender, userInput, editor, console, outputFile, null, transcript, tokenObserver);
    }

    public ChatLoop(PromptSender sender,
                    UserInput userInput,
                    EditorLauncher editor,
                    AgentConsole console,
                    Path outputFile,
                    Path completionFile,
                    Transcript transcript,
                    Consumer<Long> tokenObserver) {
        this.sender        = sender;
        this.userInput     = userInput;
        this.editor        = editor;
        this.console       = console;
        this.outputFile    = outputFile;
        this.completionFile = completionFile;
        this.outputBaseline = snapshot(outputFile);
        this.completionBaseline = completionFile != null ? snapshot(completionFile) : null;
        this.transcript    = transcript;
        this.tokenObserver = tokenObserver != null ? tokenObserver : t -> {};
    }

    public Outcome run(String openingPrompt) {
        long running = 0;

        running += send(openingPrompt, /*recordUser=*/false, /*systemPrompt=*/true);
        if (outputsComplete()) return finish(Outcome.COMPLETED, running);

        while (true) {
            printTokenCounter(running);
            String line = userInput.readLine(PROMPT);
            if (line == null) return finish(Outcome.ABORTED, running);
            if (console != null) console.reframeUserEcho(PROMPT, line);

            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if ("/abort".equals(trimmed)) {
                return finish(Outcome.ABORTED, running);
            }
            if ("/done".equals(trimmed)) {
                running += send(doneWrapUpPrompt(), /*recordUser=*/false, /*systemPrompt=*/false);
                return finish(outputsComplete() ? Outcome.COMPLETED : Outcome.DONE_FORCED, running);
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
            if (outputsComplete()) return finish(Outcome.COMPLETED, running);
        }
    }

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

    /** True when required output files were newly written or modified this session. */
    boolean outputsComplete() {
        if (!newlyWritten(outputFile, outputBaseline)) return false;
        if (completionFile == null) return true;
        return newlyWritten(completionFile, completionBaseline);
    }

    static FileBaseline snapshot(Path file) {
        if (file == null || !Files.exists(file)) {
            return new FileBaseline(false, 0L, 0L);
        }
        try {
            return new FileBaseline(true,
                Files.getLastModifiedTime(file).toMillis(),
                Files.size(file));
        } catch (IOException e) {
            return new FileBaseline(true, 0L, 0L);
        }
    }

    static boolean newlyWritten(Path file, FileBaseline baseline) {
        if (file == null || !Files.exists(file)) return false;
        if (!baseline.existed()) return true;
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size  = Files.size(file);
            return mtime > baseline.lastModified() || size != baseline.size();
        } catch (IOException e) {
            return false;
        }
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
