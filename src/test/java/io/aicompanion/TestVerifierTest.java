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
    void multipleCommandsAllPassCombinesOutput() {
        var result = new TestVerifier(
            java.util.List.of("echo lint-ok", "echo tests-ok"), CWD, 0).run();
        assertTrue(result.passed());
        assertTrue(result.output().contains("lint-ok"));
        assertTrue(result.output().contains("tests-ok"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void firstFailingCommandStopsTheRunAndIsNamed(@org.junit.jupiter.api.io.TempDir Path tmp) {
        Path marker = tmp.resolve("second-ran.txt");
        var result = new TestVerifier(java.util.List.of(
            "shell: echo lint-broken; exit 1",
            "shell: touch " + marker), CWD, 0).run();
        assertFalse(result.passed());
        assertEquals("shell: echo lint-broken; exit 1", result.command(),
            "the failing command should be reported");
        assertTrue(result.output().contains("lint-broken"));
        assertFalse(java.nio.file.Files.exists(marker),
            "commands after the first failure must not run");
    }

    @Test
    void emptyCommandListPassesWithoutRunningAnything() {
        var result = new TestVerifier(java.util.List.of(), CWD, 0).run();
        assertTrue(result.passed());
        assertTrue(result.output().contains("no test command"));
    }

    @Test
    void plainCommandSplitsOnWhitespace() {
        assertEquals(java.util.List.of("mvn", "test", "-q"),
            TestVerifier.buildArgv("mvn  test\t-q"));
    }

    @Test
    void doubleQuotedArgumentKeepsItsSpaces() {
        assertEquals(java.util.List.of("mvn", "test", "-Dtest=Foo Bar"),
            TestVerifier.buildArgv("mvn test -Dtest=\"Foo Bar\""));
    }

    @Test
    void singleQuotedArgumentKeepsItsSpaces() {
        assertEquals(java.util.List.of("npm", "run", "test one"),
            TestVerifier.buildArgv("npm run 'test one'"));
    }

    @Test
    void quotedEmptyStringSurvivesAsEmptyToken() {
        assertEquals(java.util.List.of("cmd", ""),
            TestVerifier.buildArgv("cmd \"\""));
    }

    @Test
    void unclosedQuoteRunsToEndOfCommand() {
        assertEquals(java.util.List.of("cmd", "a b"),
            TestVerifier.buildArgv("cmd \"a b"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void quotedArgumentReachesTheProcessIntact() {
        var result = new TestVerifier("echo one 'two three'", CWD).run();
        assertTrue(result.passed());
        assertEquals("one two three", result.output().trim(),
            "quotes should be stripped and the quoted arg kept as one word");
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
