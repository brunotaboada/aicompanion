package io.aicompanion.console;

import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.terminal.Terminal.SignalHandler;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.PrintWriter;

/**
 * Bottom-of-screen status bar that reserves the last terminal row using a
 * DECSTBM scroll region. Streamed runner output flows through rows 1..H-1
 * and never collides with the status row, regardless of whether writes go
 * via System.out or terminal.writer().
 *
 * <p>Tolerant of dumb / no-TTY terminals — {@link #attach} returns a no-op
 * instance when the terminal can't host a scroll region.
 */
public final class StatusBar {

    // VT100 / xterm escapes
    private static final String SAVE_CURSOR    = "\u001b7";
    private static final String RESTORE_CURSOR = "\u001b8";
    private static final String CLEAR_LINE     = "\u001b[2K";
    private static final String RESET_REGION   = "\u001b[r";

    private final Terminal terminal;
    private final PrintWriter out;
    private final SignalHandler prevWinch;
    private int height;
    private boolean closed;

    private String agent = "—";
    private String model = "(default)";
    private String task  = "—";
    private String tests = "—";
    private String fix   = "—";

    public static StatusBar attach(Terminal terminal) {
        if (terminal == null) return new StatusBar(null, null);
        int h = terminal.getHeight();
        if (h < 3) return new StatusBar(null, null);     // not enough rows to reserve one
        return new StatusBar(terminal, terminal.writer());
    }

    private StatusBar(Terminal terminal, PrintWriter out) {
        this.terminal = terminal;
        this.out      = out;
        if (terminal == null) {
            this.prevWinch = null;
            this.height    = 0;
            return;
        }
        this.height    = terminal.getHeight();
        this.prevWinch = terminal.handle(Signal.WINCH, sig -> handleResize());
        applyScrollRegion(height - 1);
        render();
    }

    public synchronized void close() {
        if (closed || terminal == null) return;
        closed = true;
        terminal.handle(Signal.WINCH, prevWinch);
        // Reset full-screen scroll, clear status row, and leave cursor on it
        // so the next prompt prints starting on a clean line.
        out.write(SAVE_CURSOR
                + String.format("\u001b[%d;1H", height)
                + CLEAR_LINE
                + RESET_REGION
                + RESTORE_CURSOR);
        out.flush();
    }

    public synchronized void setAgent(String s)            { agent = nz(s); render(); }
    public synchronized void setModel(String s)            { model = nz(s); render(); }
    public synchronized void setTask(int i, int n, String name) {
        task = i + "/" + n + "  " + nz(name); render();
    }
    public synchronized void setTestsRunning()             { tests = "running"; render(); }
    public synchronized void setTestsPass()                { tests = "PASS";    render(); }
    public synchronized void setTestsFail()                { tests = "FAIL";    render(); }
    public synchronized void setTestsSkipped()             { tests = "skipped"; render(); }
    public synchronized void setFix(int used, int max)     { fix = used + "/" + max; render(); }
    public synchronized void clearFix()                    { fix = "—";        render(); }

    private synchronized void handleResize() {
        if (terminal == null || closed) return;
        int newH = terminal.getHeight();
        if (newH < 3 || newH == height) return;
        height = newH;
        applyScrollRegion(height - 1);
        render();
    }

    /** Set scroll region to rows 1..bottom; keep status row (bottom+1) outside it. */
    private void applyScrollRegion(int bottom) {
        out.write(SAVE_CURSOR + "\u001b[1;" + bottom + "r" + RESTORE_CURSOR);
        out.flush();
    }

    private void render() {
        if (terminal == null || closed) return;
        AttributedStyle dim   = AttributedStyle.DEFAULT.faint();
        AttributedStyle label = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
        AttributedStyle ok    = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold();
        AttributedStyle bad   = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold();
        AttributedStyle plain = AttributedStyle.DEFAULT;

        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(label).append("agent ").style(plain).append(agent);
        sb.style(dim).append("  │  ");
        sb.style(label).append("model ").style(plain).append(model);
        sb.style(dim).append("  │  ");
        sb.style(label).append("task ").style(plain).append(task);
        sb.style(dim).append("  │  ");
        sb.style(label).append("tests ");
        switch (tests) {
            case "PASS" -> sb.style(ok).append(tests);
            case "FAIL" -> sb.style(bad).append(tests);
            default     -> sb.style(plain).append(tests);
        }
        sb.style(dim).append("  │  ");
        sb.style(label).append("fix ").style(plain).append(fix);

        // One atomic write: save cursor, jump to status row, clear+paint, restore.
        StringBuilder esc = new StringBuilder();
        esc.append(SAVE_CURSOR)
           .append(String.format("\u001b[%d;1H", height))
           .append(CLEAR_LINE)
           .append(sb.toAnsi(terminal))
           .append(RESTORE_CURSOR);
        out.write(esc.toString());
        out.flush();
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }
}
