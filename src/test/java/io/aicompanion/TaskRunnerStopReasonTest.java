package io.aicompanion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskRunnerStopReasonTest {

    @Test
    void successfulStops() {
        assertTrue(TaskRunner.isSuccessfulAgentStop("end_turn"));
        assertTrue(TaskRunner.isSuccessfulAgentStop("END_TURN"));
        assertTrue(TaskRunner.isSuccessfulAgentStop("stop"));
        assertTrue(TaskRunner.isSuccessfulAgentStop(null));
    }

    @Test
    void errorStops() {
        assertFalse(TaskRunner.isSuccessfulAgentStop("error"));
        assertFalse(TaskRunner.isSuccessfulAgentStop("max_tokens"));
        assertFalse(TaskRunner.isSuccessfulAgentStop("cancelled"));
    }
}
