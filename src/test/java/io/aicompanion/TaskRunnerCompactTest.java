package io.aicompanion;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.Cost;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.UsageUpdate;
import io.aicompanion.agent.SessionStats;
import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskRunnerCompactTest {

    private static final int THRESHOLD = 3;

    @Test
    void failedHandoffKeepsCompactionEligible() throws Exception {
        TaskRunner runner = runnerWithCompact();
        AcpSyncClient client = mock(AcpSyncClient.class);
        when(client.newSession(any(NewSessionRequest.class)))
            .thenReturn(new AcpSchema.NewSessionResponse("sess-2", null, null));
        when(client.prompt(any(PromptRequest.class)))
            .thenThrow(new RuntimeException("handoff failed"));

        setTasksInCurrentSession(runner, THRESHOLD);
        recentTaskNames(runner).addAll(List.of("task-a", "task-b", "task-c"));

        invokeMaybeCompactSession(runner, client, Path.of("."));

        assertEquals(THRESHOLD, tasksInCurrentSession(runner),
            "counter must stay at threshold so compaction retries");
        assertEquals(THRESHOLD, recentTaskNames(runner).size(),
            "handoff names must be preserved for retry");
        assertTrue(compactPending(runner), "failed handoff must set sticky retry flag");
        assertTrue(runner.shouldCompact(), "pending retry must keep shouldCompact true");
    }

    @Test
    void successfulHandoffResetsCompactionState() throws Exception {
        TaskRunner runner = runnerWithCompact();
        AcpSyncClient client = mock(AcpSyncClient.class);
        when(client.newSession(any(NewSessionRequest.class)))
            .thenReturn(new AcpSchema.NewSessionResponse("sess-2", null, null));
        when(client.prompt(any(PromptRequest.class)))
            .thenReturn(AcpSchema.PromptResponse.endTurn());

        setTasksInCurrentSession(runner, THRESHOLD);
        recentTaskNames(runner).addAll(List.of("task-a", "task-b", "task-c"));

        invokeMaybeCompactSession(runner, client, Path.of("."));

        assertEquals(0, tasksInCurrentSession(runner));
        assertTrue(recentTaskNames(runner).isEmpty());
        assertFalse(compactPending(runner));
        assertFalse(runner.shouldCompact());
    }

    @Test
    void contextFillTriggersCompactionWithoutTaskThreshold() throws Exception {
        TaskRunner runner = runnerWithContextPct(70);
        assertFalse(runner.shouldCompact());

        sessionStats(runner).recordUsage(usage(69_000L, 100_000L));
        assertFalse(runner.shouldCompact(), "69% should stay under a 70% threshold");

        sessionStats(runner).recordUsage(usage(70_000L, 100_000L));
        assertTrue(runner.shouldCompact());

        AcpSyncClient client = mock(AcpSyncClient.class);
        when(client.newSession(any(NewSessionRequest.class)))
            .thenReturn(new AcpSchema.NewSessionResponse("sess-ctx", null, null));
        when(client.prompt(any(PromptRequest.class)))
            .thenReturn(AcpSchema.PromptResponse.endTurn());
        recentTaskNames(runner).add("feat/task_01.md");

        invokeMaybeCompactSession(runner, client, Path.of("."));

        assertFalse(runner.shouldCompact());
        assertTrue(recentTaskNames(runner).isEmpty());
    }

    @Test
    void contextCompactionDisabledWhenPctIsZero() throws Exception {
        TaskRunner runner = runnerWithContextPct(0);
        sessionStats(runner).recordUsage(usage(99_000L, 100_000L));
        assertFalse(runner.shouldCompact());
    }

    private static TaskRunner runnerWithCompact() {
        Config cfg = ConfigLoader.load(Map.of(
            "compact_after_n_tasks", String.valueOf(THRESHOLD),
            "compact_at_context_pct", "0",
            "init_instructions", "false"
        ));
        return new TaskRunner(cfg);
    }

    private static TaskRunner runnerWithContextPct(int pct) {
        Config cfg = ConfigLoader.load(Map.of(
            "compact_after_n_tasks", "0",
            "compact_at_context_pct", String.valueOf(pct),
            "init_instructions", "false"
        ));
        return new TaskRunner(cfg);
    }

    private static UsageUpdate usage(long used, long size) {
        return new UsageUpdate("usage_update", used, size, (Cost) null, Map.of());
    }

    private static void invokeMaybeCompactSession(TaskRunner runner, AcpSyncClient client,
                                                  Path projDir) throws Exception {
        Method m = TaskRunner.class.getDeclaredMethod(
            "maybeCompactSession", AcpSyncClient.class, Path.class);
        m.setAccessible(true);
        m.invoke(runner, client, projDir);
    }

    private static int tasksInCurrentSession(TaskRunner runner) throws Exception {
        Field f = TaskRunner.class.getDeclaredField("tasksInCurrentSession");
        f.setAccessible(true);
        return f.getInt(runner);
    }

    private static void setTasksInCurrentSession(TaskRunner runner, int value) throws Exception {
        Field f = TaskRunner.class.getDeclaredField("tasksInCurrentSession");
        f.setAccessible(true);
        f.setInt(runner, value);
    }

    private static boolean compactPending(TaskRunner runner) throws Exception {
        Field f = TaskRunner.class.getDeclaredField("compactPending");
        f.setAccessible(true);
        return f.getBoolean(runner);
    }

    private static SessionStats sessionStats(TaskRunner runner) throws Exception {
        Field f = TaskRunner.class.getDeclaredField("stats");
        f.setAccessible(true);
        return (SessionStats) f.get(runner);
    }

    @SuppressWarnings("unchecked")
    private static List<String> recentTaskNames(TaskRunner runner) throws Exception {
        Field f = TaskRunner.class.getDeclaredField("recentTaskNames");
        f.setAccessible(true);
        return (List<String>) f.get(runner);
    }
}
