package io.aicompanion;

import io.aicompanion.console.Ansi;
import io.aicompanion.console.Spinner;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

/**
 * Owns all streamed-output concerns for a single TaskRunner run:
 * - gutter rendering (│ prefix on each agent line)
 * - spinner lifecycle
 * - space-bar toggle between HIDDEN and VISIBLE modes (requires a JLine terminal)
 * - the rolling summary buffer consumed by the reporter and token estimator
 *
 * Thread-safety: printAgentChunk and logEvent may be called from the ACP
 * consumer thread; the toggle is driven by a background keypress thread.
 * The outputLock guards all mutable output state.
 */
public final class AgentConsole {

    private static final String GUTTER = Ansi.dim("│ ");

    private final Terminal terminal;

    private final AtomicReference<StringBuilder> summaryBuf =
        new AtomicReference<>(new StringBuilder());
    private final AtomicReference<Spinner> activeSpinner = new AtomicReference<>();

    private volatile boolean agentLineStart  = true;
    private volatile boolean hiddenLineStart = true;

    private enum OutputMode { VISIBLE, HIDDEN }
    private volatile OutputMode outputMode;
    private final Object outputLock = new Object();
    private final StringBuilder liveBuffer = new StringBuilder();
    private volatile Thread toggleThread;

    public AgentConsole(Terminal terminal) {
        this.terminal = terminal;
    }

    /** True when a JLine terminal is available (enables the space-bar toggle). */
    public boolean hasToggle() { return terminal != null; }

    // ── summary buffer ────────────────────────────────────────────────────────

    public void resetSummary() {
        summaryBuf.set(new StringBuilder());
        agentLineStart = true;
    }

    public String getSummaryText() {
        return summaryBuf.get().toString();
    }

    // ── streaming output ──────────────────────────────────────────────────────

    public void printAgentChunk(String text) {
        if (terminal == null || outputMode == null) {
            summaryBuf.get().append(text);
            stopActiveSpinner();
            renderToStdout(text);
            return;
        }
        synchronized (outputLock) {
            summaryBuf.get().append(text);
            if (outputMode == OutputMode.HIDDEN) {
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (hiddenLineStart) { liveBuffer.append(GUTTER); hiddenLineStart = false; }
                    liveBuffer.append(c);
                    if (c == '\n') hiddenLineStart = true;
                }
            } else {
                stopActiveSpinner();
                renderToStdout(text);
            }
        }
    }

    private void renderToStdout(String text) {
        if (!Ansi.enabled()) {
            System.out.print(text);
            agentLineStart = text.endsWith("\n");
            return;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (agentLineStart) { out.append(GUTTER); agentLineStart = false; }
            out.append(c);
            if (c == '\n') agentLineStart = true;
        }
        System.out.print(out);
    }

    /**
     * Log a single event line. Buffered when toggle mode is HIDDEN;
     * printed immediately otherwise.
     */
    public void logEvent(String line) {
        if (terminal == null || outputMode == null) {
            stopActiveSpinner();
            closeAgentGutter();
            System.out.println(line);
            return;
        }
        synchronized (outputLock) {
            if (outputMode == OutputMode.HIDDEN) {
                if (!hiddenLineStart) { liveBuffer.append('\n'); hiddenLineStart = true; }
                liveBuffer.append(line).append('\n');
            } else {
                stopActiveSpinner();
                closeAgentGutter();
                System.out.println(line);
            }
        }
    }

    /** Ensures the next caller-emitted line starts on a fresh row, not after a gutter. */
    public void closeAgentGutter() {
        if (!agentLineStart) {
            System.out.println();
            agentLineStart = true;
        }
    }

    // ── spinner ───────────────────────────────────────────────────────────────

    public void startSpinner(String label) {
        Spinner s = new Spinner(label);
        activeSpinner.set(s);
        s.start();
    }

    public void stopActiveSpinner() {
        Spinner s = activeSpinner.getAndSet(null);
        if (s != null) s.stop();
    }

    // ── toggle thread (requires terminal) ────────────────────────────────────

    /**
     * Enter HIDDEN mode and start the background thread that listens for
     * [space] keypresses to toggle between HIDDEN and VISIBLE.
     */
    public void startToggle() {
        outputMode = OutputMode.HIDDEN;
        liveBuffer.setLength(0);
        hiddenLineStart = true;

        Spinner thinking = new Spinner("agent thinking  [space] show/hide");
        activeSpinner.set(thinking);
        thinking.start();

        Attributes savedAttrs = terminal.enterRawMode();
        toggleThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int ch;
                    try {
                        ch = terminal.reader().read(50L);
                    } catch (IOException e) {
                        break;
                    }
                    if (ch == ' ') toggleOutput();
                }
            } finally {
                terminal.setAttributes(savedAttrs);
            }
        }, "toggle-output");
        toggleThread.setDaemon(true);
        toggleThread.start();
    }

    /**
     * Stop the toggle thread, restore terminal state, and discard any buffered
     * HIDDEN-mode content (it was already displayed or is no longer needed).
     */
    public void stopToggle() {
        Thread t = toggleThread;
        toggleThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(300); } catch (InterruptedException ignored) {}
        }
        synchronized (outputLock) {
            stopActiveSpinner();
            liveBuffer.setLength(0);
            outputMode = null;
        }
    }

    private void toggleOutput() {
        synchronized (outputLock) {
            if (outputMode == OutputMode.HIDDEN) {
                stopActiveSpinner();
                if (!liveBuffer.isEmpty()) {
                    System.out.print(liveBuffer);
                    agentLineStart = hiddenLineStart;
                    liveBuffer.setLength(0);
                }
                outputMode = OutputMode.VISIBLE;
            } else if (outputMode == OutputMode.VISIBLE) {
                if (!agentLineStart) { System.out.println(); agentLineStart = true; }
                liveBuffer.setLength(0);
                hiddenLineStart = true;
                Spinner s = new Spinner("agent thinking  [space] show/hide");
                activeSpinner.set(s);
                s.start();
                outputMode = OutputMode.HIDDEN;
            }
        }
    }
}
