package io.aicompanion.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema.UsageUpdate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Side-channel data streamed by the agent during a session — written from the
 * ACP consumer thread, read by the runner between prompts:
 *
 * <ul>
 *   <li><b>Touched files</b> — every path the agent wrote via ACP or reported
 *       in an edit/delete/move tool-call location. The runner resets this per
 *       task so fix prompts can point the agent back at its own changes.</li>
 *   <li><b>Reported usage</b> — the latest {@link UsageUpdate} (token usage
 *       and cost). Agents that send these give exact numbers, replacing the
 *       ~4-chars/token estimator for budget checks and run summaries. Usage
 *       resets with each fresh ACP session, so {@link #rolloverSession()}
 *       folds the current session's figures into a running prior before the
 *       runner compacts.</li>
 * </ul>
 */
public final class SessionStats {

    private final Set<String> touchedFiles = new LinkedHashSet<>();

    private boolean usageReported = false;
    private long    sessionUsedTokens = 0;
    private long    priorUsedTokens   = 0;
    private double  sessionCost = 0;
    private double  priorCost   = 0;
    private String  costCurrency;
    private Long    contextSize;

    // ── touched files ─────────────────────────────────────────────────────────

    public synchronized void recordTouched(String path) {
        if (path != null && !path.isBlank()) touchedFiles.add(path);
    }

    /** Snapshot of files touched since the last {@link #resetTouched()}, in first-touch order. */
    public synchronized List<String> touchedFiles() {
        return List.copyOf(touchedFiles);
    }

    public synchronized void resetTouched() {
        touchedFiles.clear();
    }

    // ── reported usage ────────────────────────────────────────────────────────

    public synchronized void recordUsage(UsageUpdate usage) {
        if (usage == null) return;
        if (usage.used() != null) {
            sessionUsedTokens = usage.used();
            usageReported = true;
        }
        if (usage.size() != null) contextSize = usage.size();
        if (usage.cost() != null && usage.cost().amount() != null) {
            sessionCost  = usage.cost().amount();
            costCurrency = usage.cost().currency();
        }
    }

    /** True once the agent has sent at least one usage update with a token count. */
    public synchronized boolean hasReportedUsage() {
        return usageReported;
    }

    /** Reported tokens used across the whole run (prior sessions + current). */
    public synchronized long totalUsedTokens() {
        return priorUsedTokens + sessionUsedTokens;
    }

    /** Reported cost across the whole run, or {@code null} if never reported. */
    public synchronized Double totalCost() {
        double total = priorCost + sessionCost;
        return total > 0 ? total : null;
    }

    public synchronized String costCurrency() {
        return costCurrency;
    }

    /** Advertised context-window size of the current session, or {@code null}. */
    public synchronized Long contextSize() {
        return contextSize;
    }

    /** Tokens used in the <em>current</em> ACP session only (not prior compacted ones). */
    public synchronized long sessionUsedTokens() {
        return sessionUsedTokens;
    }

    /**
     * How full the current context window is, as a percentage of
     * {@link #contextSize()}, or {@code null} when the agent has not reported
     * both used-token and window-size figures.
     */
    public synchronized Integer contextFillPercent() {
        if (contextSize == null || contextSize <= 0 || !usageReported) return null;
        long pct = (sessionUsedTokens * 100L) / contextSize;
        if (pct > 100) pct = 100;
        return (int) pct;
    }

    /**
     * {@code true} when the agent has reported enough usage to know the
     * context window is at least {@code pct}% full. {@code pct <= 0} always
     * returns false (feature disabled).
     */
    public synchronized boolean contextAtOrAbove(int pct) {
        if (pct <= 0) return false;
        Integer fill = contextFillPercent();
        return fill != null && fill >= pct;
    }

    /**
     * Fold the current session's reported figures into the run totals and zero
     * the per-session counters — call just before opening a fresh ACP session,
     * whose usage reports start over from zero.
     */
    public synchronized void rolloverSession() {
        priorUsedTokens  += sessionUsedTokens;
        priorCost        += sessionCost;
        sessionUsedTokens = 0;
        sessionCost       = 0;
        contextSize       = null;
    }
}
