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

    // ── wrapPlain (frame width safety) ─────────────────────────────────────────

    @Test
    void wrapPlainKeepsShortLineIntact() {
        var lines = AgentConsole.wrapPlain("hello world", 40);
        assertEquals(1, lines.size());
        assertEquals("hello world", lines.get(0));
    }

    @Test
    void wrapPlainSoftWrapsAtSpaces() {
        var lines = AgentConsole.wrapPlain("alpha beta gamma", 11);
        // "alpha beta" = 10 cols fits; "gamma" wraps.
        assertEquals(2, lines.size());
        assertEquals("alpha beta", lines.get(0));
        assertEquals("gamma", lines.get(1));
    }

    @Test
    void wrapPlainNeverExceedsWidth() {
        String longCmd = "grep -E pattern /Users/someone/IdeaProjects/aicompanion2/src/main/java/io/aicompanion/**/*.java";
        for (String l : AgentConsole.wrapPlain(longCmd, 30)) {
            assertTrue(l.length() <= 30, "line exceeded width: <" + l + ">");
        }
    }

    @Test
    void wrapPlainHardBreaksUnbreakableToken() {
        String token = "a".repeat(50);
        var lines = AgentConsole.wrapPlain(token, 10);
        assertEquals(5, lines.size());
        for (String l : lines) assertEquals(10, l.length());
    }

    @Test
    void wrapPlainDropsLeadingSpaceOnWrappedLine() {
        var lines = AgentConsole.wrapPlain("aaaaaaaaaa bbbbbbbbbb", 10);
        assertEquals(2, lines.size());
        assertEquals("aaaaaaaaaa", lines.get(0));
        assertEquals("bbbbbbbbbb", lines.get(1));   // no leading space carried over
    }

    // ── reframedEcho (user input re-wrap) ───────────────────────────────────────

    @Test
    void reframedEchoWrapsLongLineWithinMargin() {
        String line = "Here is the thing this a feature to help customer pay for their "
                    + "products very quickly. it has to be simple to use.";
        String[] rows = AgentConsole.reframedEcho("you> ", line, 60).split("\n");
        assertTrue(rows.length > 1);
        assertTrue(rows[0].startsWith("you> "));
        for (int i = 1; i < rows.length; i++) {
            assertTrue(rows[i].startsWith("     "), "continuation not indented: <" + rows[i] + ">");
        }
        for (String r : rows) {
            assertTrue(r.length() <= 60, "row exceeded margin: <" + r + ">");
        }
    }

    @Test
    void reframedEchoKeepsWordsIntact() {
        String echoed = AgentConsole.reframedEcho("you> ", "alpha beta gamma delta", 15);
        assertEquals("you> alpha beta\n     gamma\n     delta\n", echoed);
    }

    // ── stripAnsi ───────────────────────────────────────────────────────────────

    @Test
    void stripAnsiRemovesSgrEscapes() {
        String dim = "\033[2m[perm ] ok\033[0m";
        assertEquals("[perm ] ok", AgentConsole.stripAnsi(dim));
    }

    @Test
    void stripAnsiLeavesPlainTextUnchanged() {
        assertEquals("plain text", AgentConsole.stripAnsi("plain text"));
    }
}
