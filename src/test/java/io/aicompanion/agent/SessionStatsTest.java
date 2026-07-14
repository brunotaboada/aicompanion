package io.aicompanion.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema.Cost;
import com.agentclientprotocol.sdk.spec.AcpSchema.UsageUpdate;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionStatsTest {

    private static UsageUpdate usage(Long used, Long size, Double cost) {
        return new UsageUpdate("usage_update", used, size,
            cost == null ? null : new Cost(cost, "USD"), Map.of());
    }

    @Test
    void touchedFilesDeduplicateAndKeepFirstTouchOrder() {
        var stats = new SessionStats();
        stats.recordTouched("b.java");
        stats.recordTouched("a.java");
        stats.recordTouched("b.java");
        stats.recordTouched(null);
        stats.recordTouched("  ");
        assertEquals(java.util.List.of("b.java", "a.java"), stats.touchedFiles());
    }

    @Test
    void resetTouchedClearsTheList() {
        var stats = new SessionStats();
        stats.recordTouched("a.java");
        stats.resetTouched();
        assertTrue(stats.touchedFiles().isEmpty());
    }

    @Test
    void noUsageReportedByDefault() {
        var stats = new SessionStats();
        assertFalse(stats.hasReportedUsage());
        assertEquals(0, stats.totalUsedTokens());
        assertNull(stats.totalCost());
    }

    @Test
    void latestUsageUpdateWins() {
        var stats = new SessionStats();
        stats.recordUsage(usage(100L, 200_000L, 0.10));
        stats.recordUsage(usage(2500L, 200_000L, 0.42));
        assertTrue(stats.hasReportedUsage());
        assertEquals(2500, stats.totalUsedTokens());
        assertEquals(0.42, stats.totalCost(), 1e-9);
        assertEquals("USD", stats.costCurrency());
        assertEquals(200_000L, stats.contextSize());
    }

    @Test
    void rolloverAccumulatesAcrossSessions() {
        var stats = new SessionStats();
        stats.recordUsage(usage(1000L, null, 0.10));
        stats.rolloverSession();
        assertEquals(1000, stats.totalUsedTokens());

        stats.recordUsage(usage(300L, null, 0.05));
        assertEquals(1300, stats.totalUsedTokens());
        assertEquals(0.15, stats.totalCost(), 1e-9);
    }

    @Test
    void rolloverBeforeNewSessionUsagePreservesPriorTotals() {
        var stats = new SessionStats();
        stats.recordUsage(usage(1000L, null, 0.10));
        stats.rolloverSession();
        stats.recordUsage(usage(50L, null, 0.01));
        assertEquals(1050, stats.totalUsedTokens());
        assertEquals(0.11, stats.totalCost(), 1e-9);
    }

    @Test
    void usageBeforeRolloverLosesPriorSession() {
        // Documents why rollover must precede newSession() — the ACP consumer
        // can apply the fresh session's usage_update before rollover runs.
        var stats = new SessionStats();
        stats.recordUsage(usage(1000L, null, 0.10));
        stats.recordUsage(usage(50L, null, 0.01));
        stats.rolloverSession();
        assertEquals(50, stats.totalUsedTokens());
        assertEquals(0.01, stats.totalCost(), 1e-9);
    }

    @Test
    void usageUpdateWithoutTokenCountDoesNotFlagReporting() {
        var stats = new SessionStats();
        stats.recordUsage(usage(null, 200_000L, null));
        assertFalse(stats.hasReportedUsage());
    }

    @Test
    void contextFillPercentRequiresUsedAndSize() {
        var stats = new SessionStats();
        assertNull(stats.contextFillPercent());
        stats.recordUsage(usage(70_000L, 100_000L, null));
        assertEquals(70, stats.contextFillPercent());
        assertEquals(70_000L, stats.sessionUsedTokens());
        assertTrue(stats.contextAtOrAbove(70));
        assertFalse(stats.contextAtOrAbove(71));
        assertFalse(stats.contextAtOrAbove(0));
    }

    @Test
    void contextFillCapsAt100() {
        var stats = new SessionStats();
        stats.recordUsage(usage(150_000L, 100_000L, null));
        assertEquals(100, stats.contextFillPercent());
    }

    @Test
    void rolloverClearsSessionFill() {
        var stats = new SessionStats();
        stats.recordUsage(usage(80_000L, 100_000L, null));
        assertEquals(80, stats.contextFillPercent());
        stats.rolloverSession();
        assertNull(stats.contextFillPercent());
        assertEquals(0, stats.sessionUsedTokens());
        assertEquals(80_000L, stats.totalUsedTokens());
    }
}
