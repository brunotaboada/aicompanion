package io.aicompanion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunResultTest {

    @Test
    void exitCodesForCi() {
        assertEquals(0, RunResult.success().exitCode());
        assertEquals(0, new RunResult(RunResult.Outcome.NOTHING_TO_DO, 0).exitCode());
        assertEquals(1, new RunResult(RunResult.Outcome.FAILURE, 2).exitCode());
        assertEquals(1, new RunResult(RunResult.Outcome.ABORTED, 0).exitCode());
    }
}
