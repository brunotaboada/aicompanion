package io.aicompanion;

import io.aicompanion.console.Ansi;
import io.aicompanion.console.Spinner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

/**
 * Owns all streamed-output concerns for a single TaskRunner run:
 * - frame rendering (│ on the left of every line, padded content, │ on the right)
 * - spinner lifecycle
 * - space-bar toggle between HIDDEN and VISIBLE modes (requires a JLine terminal)
 * - the rolling summary buffer consumed by the reporter and token estimator
 *
 * Thread-safety: printAgentChunk and logEvent may be called from the ACP
 * consumer thread; the toggle is driven by a background keypress thread.
 * The outputLock guards all mutable output state.
 */
public final class AgentConsole {

    private static final String GUTTER   = Ansi.dim("│ ");   // left frame, drawn at every line start
    private static final String GUTTER_R = Ansi.dim(" │");   // right frame, drawn when a line is finalized
    private static final int    FRAME_VISUAL_WIDTH = 4;      // "│ " + " │" reserved columns
    private static final int    MAX_CONTENT_WIDTH   = 96;    // text width between frames (~100 cols total) even on very wide terminals

    // ANSI SGR codes used by the streaming markdown renderer.
    private static final String BOLD_ON   = "\033[1m";
    private static final String BOLD_OFF  = "\033[22m";

    private final Terminal terminal;

    private final AtomicReference<StringBuilder> summaryBuf =
        new AtomicReference<>(new StringBuilder());
    private final AtomicReference<Spinner> activeSpinner = new AtomicReference<>();

    // Set when a tool call interrupts the agent's text so the next chunk that
    // starts a fresh text block is separated from the previous one. Without it,
    // "…working with." and "Let me check…" glue into "…working with.Let me…"
    // both in the transcript (summary buffer) and on screen (live render),
    // because the renderer holds an unflushed word across the block boundary.
    private volatile boolean summaryBreakPending = false;
    private volatile boolean renderBreakPending  = false;

    private volatile boolean agentLineStart  = true;
    private volatile boolean hiddenLineStart = true;

    // Streaming markdown renderer state (VISIBLE mode).
    private volatile int     visualCol          = 0;    // cursor col on current visual line (after gutter)
    private volatile boolean atLineStart        = true; // logical line start, expect # / - / etc.
    private volatile boolean inHeading          = false; // current logical line is a heading
    private volatile boolean inBold             = false; // inside **bold** across words
    private volatile int     partialWordFlushed = 0;    // chars of wordBuf already rendered mid-chunk
    private final StringBuilder wordBuf         = new StringBuilder();

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
        summaryBreakPending = false;
        renderBreakPending  = false;
        agentLineStart      = true;
        visualCol           = 0;
        atLineStart         = true;
        inHeading           = false;
        inBold              = false;
        partialWordFlushed  = 0;
        wordBuf.setLength(0);
    }

    public String getSummaryText() {
        return summaryBuf.get().toString();
    }

    /**
     * Signal that a tool call interrupted the agent's text. The next agent
     * chunk begins a new text block; both the summary buffer and the live
     * render insert a separating space so the blocks don't run together
     * (e.g. "…relevant files." + "Let me start…" → "…files. Let me start…").
     */
    public void markTextBlockBreak() {
        if (summaryBuf.get().length() > 0) {
            summaryBreakPending = true;
            renderBreakPending  = true;
        }
    }

    /** Append agent text to the summary buffer, honouring a pending block break. */
    private void appendSummary(String text) {
        StringBuilder buf = summaryBuf.get();
        if (summaryBreakPending) {
            summaryBreakPending = false;
            boolean bufEndsClean   = buf.length() > 0 && !Character.isWhitespace(buf.charAt(buf.length() - 1));
            boolean textStartsWord = !text.isEmpty() && !Character.isWhitespace(text.charAt(0));
            if (bufEndsClean && textStartsWord) buf.append(' ');
        }
        buf.append(text);
    }

    // ── streaming output ──────────────────────────────────────────────────────

    public void printAgentChunk(String text) {
        // Agent chunks are markdown/prose — never raw terminal control codes.
        // A stray escape (e.g. a leaked cursor/scroll sequence like ESC[1;10S)
        // would both print as garbage and desync the frame's column counter,
        // punching text past the right │. Strip them at ingestion.
        text = sanitize(text);
        // An empty chunk after sanitising must not run the renderer: doing so
        // would clear a pending text-block break before the next real chunk.
        if (text.isEmpty()) return;
        if (terminal == null || outputMode == null) {
            appendSummary(text);
            stopActiveSpinner();
            renderToStdout(text);
            return;
        }
        synchronized (outputLock) {
            appendSummary(text);
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
        boolean textStartsWord = !text.isEmpty() && !Character.isWhitespace(text.charAt(0));

        if (!Ansi.enabled()) {
            // A tool call split two text blocks: separate them with a space
            // unless the previous block ended a line or this one starts blank.
            if (renderBreakPending) {
                renderBreakPending = false;
                if (!agentLineStart && textStartsWord) System.out.print(' ');
            }
            System.out.print(text);
            agentLineStart = text.endsWith("\n");
            System.out.flush();
            return;
        }
        int width = contentWidth();
        StringBuilder out = new StringBuilder(text.length() + 16);
        // Honour a pending block break: flush the word held over from the prior
        // block, then emit a separating space so it doesn't glue to this one.
        if (renderBreakPending) {
            renderBreakPending = false;
            flushWord(out, width);
            if (!agentLineStart && visualCol > 0 && visualCol < width && textStartsWord) {
                out.append(' ');
                visualCol++;
            }
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                flushWord(out, width);
                finalizeLine(out, width);
            } else if (c == ' ' || c == '\t') {
                flushWord(out, width);
                if (agentLineStart) {
                    // Preserve indentation: gutter, then the space.
                    out.append(GUTTER);
                    agentLineStart = false;
                    out.append(' ');
                    visualCol = 1;
                } else if (visualCol >= width) {
                    // Soft-wrap at the space: drop it, close the framed line.
                    finalizeLine(out, width);
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
        // Flush the trailing word fragment immediately so each chunk appears
        // without waiting for the next space. Skip while atLineStart=true because
        // heading/bullet detection requires seeing the complete first word.
        // Hold the fragment if it would cross the right margin: emitting it now
        // would punch through the frame, so we wait for the word to complete and
        // let flushWord() soft-wrap the whole word onto the next line instead.
        // (inlineVisualLength is non-mutating so the held fragment doesn't toggle
        // the inBold state before it is actually rendered.)
        if (!atLineStart && wordBuf.length() > partialWordFlushed) {
            String partialSuffix = wordBuf.substring(partialWordFlushed);
            if (visualCol + inlineVisualLength(partialSuffix) <= width) {
                if (agentLineStart) { out.append(GUTTER); agentLineStart = false; }
                String renderedPartial = renderInline(partialSuffix);
                out.append(renderedPartial);
                visualCol += visualLength(renderedPartial);
                partialWordFlushed = wordBuf.length();
            }
        }
        System.out.print(out);
        System.out.flush();
    }

    /**
     * Close the current visual line into a frame: draw the left gutter if the
     * line is still empty (so blank separator lines keep an unbroken left bar),
     * pad the content out to {@code width}, then draw the right gutter and a
     * newline. This is what makes the block a fully aligned rectangle — the
     * left │ runs continuously and the right │ sits flush at a fixed column.
     */
    private void finalizeLine(StringBuilder out, int width) {
        if (inHeading) { out.append(BOLD_OFF); inHeading = false; }
        if (agentLineStart) { out.append(GUTTER); agentLineStart = false; }
        for (int p = visualCol; p < width; p++) out.append(' ');
        out.append(GUTTER_R);
        out.append('\n');
        agentLineStart = true;
        atLineStart    = true;
        visualCol      = 0;
    }

    private void flushWord(StringBuilder out, int width) {
        if (wordBuf.length() == 0) return;
        String word = wordBuf.toString();
        wordBuf.setLength(0);
        int prevPartial = partialWordFlushed;
        partialWordFlushed = 0;

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
                visualCol += 2;   // preserve leading indentation already counted in visualCol
                return;  // marker replaced by bullet glyph
            }
        }

        if (prevPartial > 0) {
            // The word was already partially streamed; only render the suffix.
            // emitVisual hard-breaks at the margin so a token that grew past the
            // width after it started streaming still can't escape the frame.
            String suffix = word.substring(prevPartial);
            if (!suffix.isEmpty()) emitVisual(out, renderInline(suffix), width);
            return;
        }

        String rendered = renderInline(word);
        int len = visualLength(rendered);

        if (visualCol > 0 && visualCol + len > width) {
            // Word won't fit — close this framed line before it.
            finalizeLine(out, width);
        }
        // emitVisual draws the left gutter and hard-breaks any single token that
        // is itself wider than the frame (e.g. a long backtick path).
        emitVisual(out, rendered, width);
    }

    /**
     * Append already-rendered text (which may contain ANSI SGR escapes of zero
     * visual width), drawing the left gutter at line starts and hard-breaking
     * into a new framed line whenever the content reaches the right margin. This
     * is the safety net that guarantees no glyph ever crosses the right │.
     */
    private void emitVisual(StringBuilder out, String rendered, int width) {
        int i = 0;
        while (i < rendered.length()) {
            char c = rendered.charAt(i);
            if (c == '\033' && i + 1 < rendered.length() && rendered.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < rendered.length() && rendered.charAt(j) != 'm') j++;
                if (j < rendered.length()) j++;
                out.append(rendered, i, j);   // SGR escape — no column advance
                i = j;
                continue;
            }
            if (visualCol >= width) finalizeLine(out, width);
            if (agentLineStart) { out.append(GUTTER); agentLineStart = false; }
            out.append(c);
            visualCol++;
            i++;
        }
    }

    /** Visual width of a word's inline-styled form without mutating render state. */
    private static int inlineVisualLength(String word) {
        int n = 0, i = 0;
        while (i < word.length()) {
            if (word.charAt(i) == '*' && i + 1 < word.length() && word.charAt(i + 1) == '*') {
                i += 2;   // ** markers are consumed, not rendered
            } else {
                n++;
                i++;
            }
        }
        return n;
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
        int avail = Math.max(20, w - FRAME_VISUAL_WIDTH);
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
            printLogLine(line);
            return;
        }
        synchronized (outputLock) {
            if (outputMode == OutputMode.HIDDEN) {
                if (!hiddenLineStart) { liveBuffer.append('\n'); hiddenLineStart = true; }
                liveBuffer.append(line).append('\n');
            } else {
                stopActiveSpinner();
                closeAgentGutter();
                printLogLine(line);
            }
        }
    }

    /**
     * Print a complete log/event line inside the same frame as agent prose:
     * word-wrapped (long tokens like paths/commands hard-break) to the content
     * width, with the left and right │ drawn so the line can never overflow the
     * frame. Log lines are uniformly dim, so we strip any incoming ANSI and
     * re-apply {@code Ansi.dim} per wrapped line, keeping each line's styling
     * self-contained. Falls back to a raw println when ANSI is disabled.
     */
    private void printLogLine(String line) {
        if (!Ansi.enabled()) { System.out.println(line); return; }
        int width = contentWidth();
        StringBuilder out = new StringBuilder(line.length() + 16);
        for (String seg : wrapPlain(stripAnsi(line), width)) {
            out.append(GUTTER).append(Ansi.dim(seg));
            for (int p = seg.length(); p < width; p++) out.append(' ');
            out.append(GUTTER_R).append('\n');
        }
        System.out.print(out);
        System.out.flush();
    }

    /**
     * Re-echo a just-submitted input line wrapped to the frame's right margin.
     * JLine echoes input at the full terminal width, so on a wide terminal a
     * long {@code you>} line punches past the right │ of the agent frame.
     * After Enter the echo is already on screen; erase the rows it occupied
     * and reprint the line wrapped to the same margin the frame uses.
     * No-op when the echo already fits or when ANSI/terminal is unavailable.
     */
    public void reframeUserEcho(String prompt, String line) {
        if (!Ansi.enabled() || terminal == null) return;
        int termWidth = terminal.getWidth();
        if (termWidth <= 0) termWidth = 80;
        int margin = Math.min(contentWidth() + FRAME_VISUAL_WIDTH, termWidth);
        if (prompt.length() + line.length() <= margin) return;
        // Cursor sits at column 0 just below the echo; the echo occupied
        // ceil(totalLen / termWidth) rows. Move up over them and clear down.
        int rows = (prompt.length() + line.length() + termWidth - 1) / termWidth;
        System.out.print("\033[" + rows + "A\033[J" + reframedEcho(prompt, line, margin));
        System.out.flush();
    }

    /** The wrapped replacement echo: prompt on the first line, continuation lines indented under it. */
    static String reframedEcho(String prompt, String line, int margin) {
        String indent = " ".repeat(prompt.length());
        StringBuilder out = new StringBuilder(line.length() + 32);
        List<String> segs = wrapPlain(line, margin - prompt.length());
        for (int i = 0; i < segs.size(); i++) {
            out.append(i == 0 ? prompt : indent).append(segs.get(i)).append('\n');
        }
        return out.toString();
    }

    /** Word-wrap plain text to {@code width}, hard-breaking any word wider than it. */
    static List<String> wrapPlain(String s, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int col = 0, i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                if (col == 0)            { i++; continue; }   // drop leading space on a wrapped line
                if (col >= width)        { pushLine(lines, line); col = 0; i++; continue; }
                line.append(' '); col++; i++;
                continue;
            }
            int j = i;
            while (j < n && s.charAt(j) != ' ' && s.charAt(j) != '\t') j++;
            String word = s.substring(i, j);
            i = j;
            if (col > 0 && col + word.length() > width) {     // soft-wrap before a word that fits alone
                pushLine(lines, line); col = 0;
            }
            for (int k = 0; k < word.length(); k++) {         // hard-break a word wider than the frame
                if (col >= width) { pushLine(lines, line); col = 0; }
                line.append(word.charAt(k)); col++;
            }
        }
        pushLine(lines, line);
        return lines;
    }

    /** Append {@code line} (trailing spaces trimmed) to {@code lines} and reset the buffer. */
    private static void pushLine(List<String> lines, StringBuilder line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') end--;
        lines.add(line.substring(0, end));
        line.setLength(0);
    }

    /**
     * Strip terminal control sequences and stray control characters from raw
     * agent text, keeping only printable content plus {@code \n} and {@code \t}.
     * Removes full ESC-introduced sequences:
     * <ul>
     *   <li>CSI ({@code ESC [ … final}) — final byte in 0x40–0x7E, e.g. colour,
     *       cursor-move, scroll, erase. Covers the {@code ESC[1;10S} that leaked
     *       into the stream and broke the frame.</li>
     *   <li>OSC ({@code ESC ] … BEL|ST}) — title/hyperlink sequences.</li>
     *   <li>Any other {@code ESC} + one byte (charset selects, etc.).</li>
     * </ul>
     * The agent's own markdown ({@code **bold**}) is untouched — our SGR styling
     * is added later in {@link #renderInline}, after this runs.
     */
    static String sanitize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\033') {                        // ESC: start of a sequence
                i++;
                if (i >= n) break;
                char kind = s.charAt(i++);
                if (kind == '[') {                    // CSI — run to a final byte
                    while (i < n && (s.charAt(i) < 0x40 || s.charAt(i) > 0x7E)) i++;
                    if (i < n) i++;                   // consume the final byte
                } else if (kind == ']') {             // OSC — run to BEL or ST (ESC \)
                    while (i < n && s.charAt(i) != '\007'
                            && !(s.charAt(i) == '\033' && i + 1 < n && s.charAt(i + 1) == '\\')) i++;
                    if (i < n && s.charAt(i) == '\033') i++;
                    if (i < n) i++;                   // consume BEL or the '\' of ST
                }
                // else: ESC + single byte already consumed
                continue;
            }
            if (c == '\n' || c == '\t') { b.append(c); i++; continue; }
            if (c < 0x20 || c == 0x7F)  { i++; continue; }   // drop other C0/DEL
            b.append(c);
            i++;
        }
        return b.toString();
    }

    /** Remove ANSI SGR escapes, leaving only visible characters. */
    static String stripAnsi(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\033' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                i += 2;
                while (i < s.length() && s.charAt(i) != 'm') i++;
                if (i < s.length()) i++;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    /** Ensures the next caller-emitted line starts on a fresh row, not after a gutter. */
    public void closeAgentGutter() {
        int width = contentWidth();
        StringBuilder out = new StringBuilder();
        if (wordBuf.length() > 0) flushWord(out, width);
        if (!agentLineStart) {
            finalizeLine(out, width);
        }
        if (out.length() > 0) System.out.print(out);
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
                    System.out.flush();
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
