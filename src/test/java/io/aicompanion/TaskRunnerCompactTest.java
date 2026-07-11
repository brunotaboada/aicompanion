package io.aicompanion;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }

    private static TaskRunner runnerWithCompact() {
        Config cfg = ConfigLoader.load(Map.of(
            "compact_after_n_tasks", String.valueOf(THRESHOLD),
            "init_instructions", "false"
        ));
        return new TaskRunner(cfg);
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

    @SuppressWarnings("unchecked")
    private static List<String> recentTaskNames(TaskRunner runner) throws Exception {
        Field f = TaskRunner.class.getDeclaredField("recentTaskNames");
        f.setAccessible(true);
        return (List<String>) f.get(runner);
    }
}
