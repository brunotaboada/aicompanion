package io.aicompanion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskRunnerSessionTest {

    @Test
    void freshSessionIsNeverRefreshed() {
        assertFalse(TaskRunner.shouldRefreshSession(true,  3, 0));
        assertFalse(TaskRunner.shouldRefreshSession(false, 0, 0));
    }

    @Test
    void reuseSessionFalseRefreshesAfterEveryTask() {
        assertTrue(TaskRunner.shouldRefreshSession(false, 0, 1));
        assertTrue(TaskRunner.shouldRefreshSession(false, 0, 2));
    }

    @Test
    void reuseSessionTrueWithoutCompactNeverRefreshes() {
        assertFalse(TaskRunner.shouldRefreshSession(true, 0, 1));
        assertFalse(TaskRunner.shouldRefreshSession(true, 0, 100));
    }

    @Test
    void compactThresholdRefreshesOnceReached() {
        assertFalse(TaskRunner.shouldRefreshSession(true, 3, 2));
        assertTrue(TaskRunner.shouldRefreshSession(true, 3, 3));
        assertTrue(TaskRunner.shouldRefreshSession(true, 3, 4));
    }
}
