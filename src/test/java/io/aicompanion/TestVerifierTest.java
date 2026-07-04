package io.aicompanion;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

class TestVerifierTest {

    private static final Path CWD = Path.of(".");

    @Test
    void noCommandPassesWithoutRunningAnything() {
        var result = new TestVerifier(null, CWD).run();
        assertTrue(result.passed());
        assertTrue(result.output().contains("no test command"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void passingCommandReportsPassedWithOutput() {
        var result = new TestVerifier("shell: echo hello", CWD).run();
        assertTrue(result.passed());
        assertTrue(result.output().contains("hello"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void failingCommandReportsFailed() {
        var result = new TestVerifier("shell: exit 3", CWD).run();
        assertFalse(result.passed());
    }

    @Test
    @Timeout(30)
    @DisabledOnOs(OS.WINDOWS)
    void timedOutCommandIsKilledAndReportsFailure() {
        // Millisecond-granular constructor: a 2s cap on a 10-minute sleep.
        var result = new TestVerifier("shell: echo started; sleep 600", CWD, 2_000L).run();
        assertFalse(result.passed());
        assertTrue(result.output().contains("timed out"),
            "output should say the command timed out: " + result.output());
        assertTrue(result.output().contains("started"),
            "partial output captured before the kill should be included");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void interruptDoesNotMaskAsGenericRunnerError() throws Exception {
        var verifier = new TestVerifier("shell: sleep 30", CWD);
        final TestVerifier.Result[] out = new TestVerifier.Result[1];
        Thread t = new Thread(() -> out[0] = verifier.run());
        t.start();
        Thread.sleep(500);      // let the process start
        t.interrupt();
        t.join(10_000);
        assertFalse(t.isAlive(), "runner thread should exit promptly on interrupt");
        assertFalse(out[0].passed());
        assertTrue(out[0].output().contains("interrupted"),
            "interrupt should be reported as such: " + out[0].output());
    }
}
