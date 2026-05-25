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
    private static final int    GUTTER_VISUAL_WIDTH = 2;     // "│ " when rendered
    private static final int    MAX_CONTENT_WIDTH   = 100;   // wrap prose at ~100 cols even on very wide terminals

    // ANSI SGR codes used by the streaming markdown renderer.
    private static final String BOLD_ON   = "\033[1m";
    private static final String BOLD_OFF  = "\033[22m";

    private final Terminal terminal;

    private final AtomicReference<StringBuilder> summaryBuf =
        new AtomicReference<>(new StringBuilder());
    private final AtomicReference<Spinner> activeSpinner = new AtomicReference<>();

    private volatile boolean agentLineStart  = true;
    private volatile boolean hiddenLineStart = true;

    // Streaming markdown renderer state (VISIBLE mode).
    private volatile int     visualCol    = 0;     // cursor col on current visual line (after gutter)
    private volatile boolean atLineStart  = true;  // logical line start, expect # / - / etc.
    private volatile boolean inHeading    = false; // current logical line is a heading
    private volatile boolean inBold       = false; // inside **bold** across words
    private final StringBuilder wordBuf   = new StringBuilder();

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
        visualCol      = 0;
        atLineStart    = true;
        inHeading      = false;
        inBold         = false;
        wordBuf.setLength(0);
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

    /**
     * Stream agent output to stdout with the agent gutter, soft-wrap at a
     * readable prose width, and lightweight markdown styling:
     *   `# ` / `## ` / `### `   → bold heading line
     *   `- ` / `* ` at line head → `• ` bullet
     *   `**bold**`               → ANSI bold
     *
     * Backticks pass through literally — no highlight, no styling. Wrap is
     * deferred until a whitespace boundary so we never split a word.
     * {@code **} that straddles chunks is tolerated because {@code inBold}
     * lives on the instance.
     */
    private void renderToStdout(String text) {
        if (!Ansi.enabled()) {
            System.out.print(text);
            agentLineStart = text.endsWith("\n");
            return;
        }
        int width = contentWidth();
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                flushWord(out, width);
                if (inHeading) { out.append(BOLD_OFF); inHeading = false; }
                out.append('\n');
                agentLineStart = true;
                atLineStart    = true;
                visualCol      = 0;
            } else if (c == ' ' || c == '\t') {
                flushWord(out, width);
                if (agentLineStart) {
                    // Preserve indentation: gutter, then the space.
                    out.append(GUTTER);
                    agentLineStart = false;
                    out.append(' ');
                    visualCol = 1;
                } else if (visualCol >= width) {
                    // Soft-wrap at the space: drop it, start a new line.
                    out.append('\n');
                    agentLineStart = true;
                    visualCol      = 0;
                } else {
                    out.append(' ');
                    visualCol++;
                }
            } else {
                wordBuf.append(c);
                // Bound the word buffer so a pathological no-whitespace blob
                // can't grow without limit. 200 chars is generous for prose.
                if (wordBuf.length() > 200) flushWord(out, width);
            }
        }
        System.out.print(out);
    }

    private void flushWord(StringBuilder out, int width) {
        if (wordBuf.length() == 0) return;
        String word = wordBuf.toString();
        wordBuf.setLength(0);

        if (atLineStart) {
            atLineStart = false;
            if (word.equals("#") || word.equals("##") || word.equals("###")) {
                if (agentLineStart) { out.append(GUTTER); agentLineStart = false; }
                out.append(BOLD_ON);
                inHeading = true;
                return;  // eat the marker — heading text follows
            }
            if (word.equals("-") || word.equals("*")) {
                if (agentLineStart) { out.append(GUTTER); agentLineStart = false; }
                out.append("• ");
                visualCol = 2;
                return;  // marker replaced by bullet glyph
            }
        }

        String rendered = renderInline(word);
        int len = visualLength(rendered);

        if (visualCol > 0 && visualCol + len > width) {
            // Word won't fit — wrap before it.
            out.append('\n');
            agentLineStart = true;
            visualCol      = 0;
        }
        if (agentLineStart) { out.append(GUTTER); agentLineStart = false; }
        out.append(rendered);
        visualCol += len;
    }

    /** Apply inline markdown styling to a single whitespace-delimited word. */
    private String renderInline(String word) {
        StringBuilder sb = new StringBuilder(word.length());
        int i = 0;
        while (i < word.length()) {
            char c = word.charAt(i);
            if (c == '*' && i + 1 < word.length() && word.charAt(i + 1) == '*') {
                sb.append(inBold ? BOLD_OFF : BOLD_ON);
                inBold = !inBold;
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** Visible column width of a rendered string, ignoring ANSI SGR escapes. */
    private static int visualLength(String s) {
        int n = 0, i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\033' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                i += 2;
                while (i < s.length() && s.charAt(i) != 'm') i++;
                if (i < s.length()) i++;
            } else {
                n++;
                i++;
            }
        }
        return n;
    }

    private int contentWidth() {
        int w = terminal != null ? terminal.getWidth() : 0;
        if (w <= 0) w = 80;
        int avail = Math.max(20, w - GUTTER_VISUAL_WIDTH);
        return Math.min(avail, MAX_CONTENT_WIDTH);
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
        if (wordBuf.length() > 0) {
            StringBuilder out = new StringBuilder();
            flushWord(out, contentWidth());
            if (out.length() > 0) System.out.print(out);
        }
        if (!agentLineStart) {
            if (inHeading) { System.out.print(BOLD_OFF); inHeading = false; }
            System.out.println();
            agentLineStart = true;
            atLineStart    = true;
            visualCol      = 0;
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

        Spinner working = new Spinner("agent working  [space] show/hide");
        activeSpinner.set(working);
        working.start();

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
                Spinner s = new Spinner("agent working  [space] show/hide");
                activeSpinner.set(s);
                s.start();
                outputMode = OutputMode.HIDDEN;
            }
        }
    }
}
