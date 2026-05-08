package io.aicompanion.console;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.util.List;

/**
 * Bottom-of-screen status bar showing live runner state: agent, model,
 * current task, last test result, and fix attempts. Backed by JLine's
 * {@link Status}, which pins lines below a scroll region so streamed
 * agent output keeps flowing above it.
 *
 * <p>Tolerant of dumb / no-TTY terminals — {@link #attach} returns a
 * no-op instance and every setter / {@link #close} becomes a cheap
 * fall-through, so callers don't need to gate on TTY themselves.
 */
public final class StatusBar {

    private static final StatusBar INACTIVE = new StatusBar(null);

    private final Status status;
    private String agent = "—";
    private String model = "(default)";
    private String task  = "—";
    private String tests = "—";
    private String fix   = "—";

    private StatusBar(Status status) {
        this.status = status;
    }

    /** Returns a live status bar, or a no-op one if the terminal can't host it. */
    public static StatusBar attach(Terminal terminal) {
        if (terminal == null) return INACTIVE;
        try {
            Status s = Status.getStatus(terminal, true);
            return s != null ? new StatusBar(s) : INACTIVE;
        } catch (Throwable t) {
            return INACTIVE;
        }
    }

    public void setAgent(String a)            { this.agent = nz(a); render(); }
    public void setModel(String m)            { this.model = nz(m); render(); }
    public void setTask(int i, int n, String name) {
        this.task = i + "/" + n + "  " + nz(name); render();
    }
    public void setTestsRunning()             { this.tests = "running"; render(); }
    public void setTestsPass()                { this.tests = "PASS";    render(); }
    public void setTestsFail()                { this.tests = "FAIL";    render(); }
    public void setTestsSkipped()             { this.tests = "skipped"; render(); }
    public void setFix(int used, int max)     { this.fix = used + "/" + max; render(); }
    public void clearFix()                    { this.fix = "—";        render(); }

    public void close() {
        if (status == null) return;
        try { status.close(); } catch (Throwable ignored) {}
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    private void render() {
        if (status == null) return;
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

        try {
            status.update(List.of(sb.toAttributedString()));
        } catch (Throwable ignored) {
            // Terminal disconnected mid-render — silently drop.
        }
    }

    /** Pre-render a one-shot snapshot for {@link AttributedString} consumers (e.g., tests). */
    AttributedString snapshot() {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.append("agent=").append(agent)
          .append(" model=").append(model)
          .append(" task=").append(task)
          .append(" tests=").append(tests)
          .append(" fix=").append(fix);
        return sb.toAttributedString();
    }
}
