package io.aicompanion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PromptsTest {

    @Test
    void forTaskAppendsCompactSummaryRules() {
        String p = Prompts.forTask("Do the thing.");
        assertTrue(p.startsWith("Do the thing."));
        assertTrue(p.contains("3-5"));
        assertTrue(p.contains("bullets"));
        assertFalse(p.contains("Format requirements:"),
            "compact template should drop the old verbose label");
    }

    @Test
    void forTaskShortIsShorterThanForTask() {
        String body = "Task body here.";
        assertTrue(Prompts.forTaskShort(body).length() < Prompts.forTask(body).length(),
            "the short variant must save tokens when init has been sent");
    }

    @Test
    void forFixTruncatesLongOutput() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) big.append("line ").append(i).append('\n');
        String fix = Prompts.forFix("mvn test", big.toString(), 200);
        assertTrue(fix.contains("line(s) elided"),
            "long output should be elided in the middle");
        // Roughly: header lines + 200 retained + ellipsis + footer < 1000 lines.
        int approxLines = fix.split("\n", -1).length;
        assertTrue(approxLines < 300,
            "truncated prompt should not still contain 1000 output lines: " + approxLines);
    }

    @Test
    void forFixUnlimitedWhenMaxLinesIsZero() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 50; i++) big.append("err ").append(i).append('\n');
        String fix = Prompts.forFix("mvn test", big.toString(), 0);
        assertFalse(fix.contains("line(s) elided"));
        for (int i = 0; i < 50; i++) assertTrue(fix.contains("err " + i));
    }

    @Test
    void truncateOutputKeepsHeadAndTail() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 100; i++) big.append("L").append(i).append('\n');
        String t = Prompts.truncateOutput(big.toString(), 20);
        assertTrue(t.contains("L0"),  "head should be retained");
        assertTrue(t.contains("L99"), "tail should be retained");
        assertFalse(t.contains("L50"), "middle should be elided");
        assertTrue(t.contains("line(s) elided"));
    }

    @Test
    void truncateOutputShortInputUnchanged() {
        String s = "only three\nshort\nlines";
        assertEquals(s, Prompts.truncateOutput(s, 100));
    }

    @Test
    void stripPreambleDropsContentBeforeFirstHeading() {
        String body = """
            This is the feature spec.
            Some prose that gets repeated every task.

            # Task 1
            Do the actual thing.
            """;
        String stripped = Prompts.stripPreamble(body);
        assertTrue(stripped.startsWith("# Task 1"));
        assertFalse(stripped.contains("feature spec"));
    }

    @Test
    void stripPreambleLeavesHeadlineOnlyContentAlone() {
        String body = "# Already starts with heading\nbody";
        assertEquals(body, Prompts.stripPreamble(body));
    }

    @Test
    void stripPreambleLeavesPlainTextAlone() {
        String body = "no headings here at all\njust prose";
        assertEquals(body, Prompts.stripPreamble(body));
    }

    @Test
    void forSessionInitContainsFormatRules() {
        String init = Prompts.forSessionInit();
        assertTrue(init.contains("3-5"));
        assertTrue(init.contains("bullet"));
    }

    @Test
    void forCompactHandoffMentionsCompletedTasks() {
        String handoff = Prompts.forCompactHandoff(
            java.util.List.of("auth/01-login.md", "auth/02-logout.md"));
        assertTrue(handoff.contains("auth/01-login.md"));
        assertTrue(handoff.contains("auth/02-logout.md"));
        assertTrue(handoff.toLowerCase().contains("completed"));
    }

    @Test
    void forCompactHandoffHandlesEmptyList() {
        String handoff = Prompts.forCompactHandoff(java.util.List.of());
        assertNotNull(handoff);
        assertFalse(handoff.isBlank());
    }
}
