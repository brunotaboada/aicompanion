package io.aicompanion.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatLoopTest {

    @TempDir Path tempDir;

    /** Drives the loop via a script of user lines + a side-effect on each agent turn. */
    private ChatLoop loop(Path output, List<String> userScript, Runnable... agentSideEffects) {
        int[] turn = {0};
        ChatLoop.PromptSender sender = prompt -> {
            if (turn[0] < agentSideEffects.length && agentSideEffects[turn[0]] != null) {
                agentSideEffects[turn[0]].run();
            }
            turn[0]++;
        };
        Iterator<String> it = userScript.iterator();
        UserInput input = prompt -> it.hasNext() ? it.next() : null;
        EditorLauncher editor = new EditorLauncher((cmd, file) -> {
            Files.writeString(file, "long answer from $EDITOR");
            return 0;
        });
        return new ChatLoop(sender, input, editor, null, output, null, null);
    }

    @Test
    void exitsCompletedWhenAgentWritesOutputOnOpeningTurn() {
        Path out = tempDir.resolve("_prd.md");
        ChatLoop l = loop(out, List.of(),
            () -> writeFile(out, "the PRD"));   // opening prompt causes the write

        assertEquals(ChatLoop.Outcome.COMPLETED, l.run("SKILL.md body"));
    }

    @Test
    void exitsCompletedAfterSeveralTurns() {
        Path out = tempDir.resolve("_prd.md");
        ChatLoop l = loop(out,
            List.of("A", "B", "Approve"),
            null, null, null,             // first three turns: agent silent
            () -> writeFile(out, "draft") // fourth turn: agent writes
        );
        assertEquals(ChatLoop.Outcome.COMPLETED, l.run("opening"));
    }

    @Test
    void abortSentinelExitsWithoutWriting() {
        Path out = tempDir.resolve("_prd.md");
        ChatLoop l = loop(out, List.of("/abort"));
        assertEquals(ChatLoop.Outcome.ABORTED, l.run("opening"));
        assertFalse(Files.exists(out));
    }

    @Test
    void doneSentinelTriggersWrapUpPromptThenExits() {
        Path out = tempDir.resolve("_prd.md");
        List<String> sentPrompts = new ArrayList<>();
        ChatLoop.PromptSender capture = sentPrompts::add;
        Iterator<String> it = List.of("/done").iterator();
        UserInput input = p -> it.hasNext() ? it.next() : null;
        EditorLauncher editor = new EditorLauncher((c, f) -> 0);

        ChatLoop l = new ChatLoop(capture, input, editor, null, out, null, null);

        ChatLoop.Outcome outcome = l.run("opening");

        assertEquals(ChatLoop.Outcome.DONE_FORCED, outcome);
        assertEquals(2, sentPrompts.size(),
            "should have shipped opening + the wrap-up prompt");
        assertTrue(sentPrompts.get(1).contains("/done"),
            "wrap-up prompt should mention the sentinel: " + sentPrompts.get(1));
    }

    @Test
    void doneSentinelReturnsCompletedWhenAgentActuallyWritesOnWrapUp() {
        Path out = tempDir.resolve("_prd.md");
        int[] turn = {0};
        ChatLoop.PromptSender sender = p -> {
            if (turn[0] == 1) writeFile(out, "drafted");  // wrap-up turn writes
            turn[0]++;
        };
        Iterator<String> it = List.of("/done").iterator();
        UserInput input = p -> it.hasNext() ? it.next() : null;

        ChatLoop l = new ChatLoop(
            sender, input, new EditorLauncher((c, f) -> 0), null, out, null, null);

        assertEquals(ChatLoop.Outcome.COMPLETED, l.run("opening"));
    }

    @Test
    void skipSentinelRelaysAsNoPreferenceText() {
        Path out = tempDir.resolve("_prd.md");
        List<String> sentPrompts = new ArrayList<>();
        ChatLoop.PromptSender capture = sentPrompts::add;
        Iterator<String> it = List.of("/skip", "/abort").iterator();
        UserInput input = p -> it.hasNext() ? it.next() : null;

        ChatLoop l = new ChatLoop(
            capture, input, new EditorLauncher((c, f) -> 0), null, out, null, null);
        l.run("opening");

        assertEquals(2, sentPrompts.size());
        assertTrue(sentPrompts.get(1).contains("strong preference"),
            "expected the /skip rephrase, got: " + sentPrompts.get(1));
    }

    @Test
    void editSentinelOpensEditorAndShipsItsContents() {
        Path out = tempDir.resolve("_prd.md");
        List<String> sentPrompts = new ArrayList<>();
        ChatLoop.PromptSender capture = sentPrompts::add;
        Iterator<String> it = List.of("/edit", "/abort").iterator();
        UserInput input = p -> it.hasNext() ? it.next() : null;
        EditorLauncher editor = new EditorLauncher((cmd, file) -> {
            Files.writeString(file, "multi-line\nanswer from editor\n");
            return 0;
        });

        ChatLoop l = new ChatLoop(capture, input, editor, null, out, null, null);
        l.run("opening");

        assertEquals(2, sentPrompts.size());
        assertEquals("multi-line\nanswer from editor", sentPrompts.get(1));
    }

    @Test
    void editSentinelCancelDoesNotShipAndLoopContinues() {
        Path out = tempDir.resolve("_prd.md");
        List<String> sentPrompts = new ArrayList<>();
        ChatLoop.PromptSender capture = sentPrompts::add;
        Iterator<String> it = List.of("/edit", "real answer", "/abort").iterator();
        UserInput input = p -> it.hasNext() ? it.next() : null;
        EditorLauncher editor = new EditorLauncher((cmd, file) -> 1); // cancel

        ChatLoop l = new ChatLoop(capture, input, editor, null, out, null, null);
        l.run("opening");

        assertEquals(2, sentPrompts.size(),
            "opening + 'real answer' only; cancelled /edit must not send");
        assertEquals("real answer", sentPrompts.get(1));
    }

    @Test
    void eofFromUserInputCountsAsAbort() {
        Path out = tempDir.resolve("_prd.md");
        ChatLoop l = loop(out, List.of());  // immediately returns null on readLine
        assertEquals(ChatLoop.Outcome.ABORTED, l.run("opening"));
    }

    @Test
    void formatTokensIsHumanReadable() {
        assertEquals("950",    ChatLoop.formatTokens(950));
        assertEquals("1.5k",   ChatLoop.formatTokens(1500));
        assertEquals("12.3k",  ChatLoop.formatTokens(12345));
    }

    private static void writeFile(Path p, String content) {
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
