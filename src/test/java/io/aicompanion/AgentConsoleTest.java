package io.aicompanion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests AgentConsole in no-terminal mode (terminal == null), which exercises
 * the raw pass-through path and the summary buffer independently of ANSI rendering.
 */
class AgentConsoleTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream captured;
    private AgentConsole console;

    @BeforeEach
    void setUp() {
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        console = new AgentConsole(null);   // no-terminal mode
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    // ── summary buffer ────────────────────────────────────────────────────────

    @Test
    void printAgentChunkAccumulatesInSummary() {
        console.printAgentChunk("hello ");
        console.printAgentChunk("world");
        assertEquals("hello world", console.getSummaryText());
    }

    @Test
    void resetSummaryClearsBuffer() {
        console.printAgentChunk("some text");
        console.resetSummary();
        assertEquals("", console.getSummaryText());
    }

    @Test
    void getSummaryTextIsEmptyBeforeAnyChunk() {
        assertEquals("", console.getSummaryText());
    }

    @Test
    void multipleChunksAreConcatenated() {
        for (int i = 0; i < 5; i++) console.printAgentChunk("x");
        assertEquals("xxxxx", console.getSummaryText());
    }

    @Test
    void summaryPreservesNewlines() {
        console.printAgentChunk("line1\nline2\n");
        assertEquals("line1\nline2\n", console.getSummaryText());
    }

    // ── stdout pass-through (no-terminal, no ANSI) ───────────────────────────

    @Test
    void printAgentChunkWritesToStdout() {
        console.printAgentChunk("agent output");
        assertTrue(captured.toString().contains("agent output"));
    }

    @Test
    void closeAgentGutterOnEmptyStateDoesNotCrash() {
        // Should not throw and should not produce any output when nothing was written.
        assertDoesNotThrow(() -> console.closeAgentGutter());
    }

    @Test
    void closeAgentGutterAddsNewlineAfterPartialLine() {
        console.printAgentChunk("partial");  // no trailing newline
        console.closeAgentGutter();
        // After closing the gutter a newline should have been emitted.
        String out = captured.toString();
        assertTrue(out.endsWith("\n"), "expected trailing newline after closeAgentGutter()");
    }

    @Test
    void logEventWritesToStdout() {
        console.logEvent("[tool] read file");
        assertTrue(captured.toString().contains("[tool] read file"));
    }

    @Test
    void stopActiveSpinnerOnEmptyStateDoesNotCrash() {
        assertDoesNotThrow(() -> console.stopActiveSpinner());
    }

    // ── hasToggle ─────────────────────────────────────────────────────────────

    @Test
    void hasToggleReturnsFalseWithoutTerminal() {
        assertFalse(console.hasToggle());
    }
}
