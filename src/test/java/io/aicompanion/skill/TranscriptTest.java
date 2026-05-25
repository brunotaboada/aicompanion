package io.aicompanion.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class TranscriptTest {

    @TempDir Path tempDir;

    @Test
    void writesHeaderUserAgentAndOutcomeInOrder() throws IOException {
        Path file = tempDir.resolve("features/auth/_prd.transcript.md");
        Transcript t = new Transcript(file);

        t.recordHeader("create-prd", "auth");
        t.recordOpeningPrompt("You are running create-prd...");
        t.recordAgent("Who are the users?");
        t.recordUser("End users on a web app");
        t.recordAgent("Got it.");
        t.recordOutcome("completed");

        String all = Files.readString(file);
        assertTrue(all.startsWith("# Transcript: create-prd · auth"), all);
        // turns appear in the order they were recorded
        int i1 = all.indexOf("You are running create-prd");
        int i2 = all.indexOf("Who are the users?");
        int i3 = all.indexOf("End users on a web app");
        int i4 = all.indexOf("Got it.");
        int i5 = all.indexOf("**Outcome:** completed");
        assertTrue(i1 > 0 && i2 > i1 && i3 > i2 && i4 > i3 && i5 > i4,
            "turns out of order: " + all);
    }

    @Test
    void createsParentDirectoriesOnFirstWrite() throws IOException {
        Path file = tempDir.resolve("deep/nested/dir/t.md");
        new Transcript(file).recordHeader("s", "f");
        assertTrue(Files.exists(file));
    }

    @Test
    void agentTurnIsSkippedWhenTextIsBlank() throws IOException {
        Path file = tempDir.resolve("t.md");
        Transcript t = new Transcript(file);
        t.recordHeader("s", "f");
        t.recordAgent("");
        t.recordAgent(null);
        t.recordAgent("real reply");

        String all = Files.readString(file);
        long agentSections = all.lines().filter(l -> l.equals("### agent")).count();
        assertEquals(1, agentSections, "only the real reply should produce a section");
    }

    @Test
    void openingPromptIsFencedAndInnerTripleBacktickEscaped() throws IOException {
        Path file = tempDir.resolve("t.md");
        new Transcript(file).recordOpeningPrompt("here is a fence:\n```code```\nand text");
        String all = Files.readString(file);
        // outer fence is preserved
        assertTrue(all.contains("```\nhere is a fence:"), all);
        // inner triple-backtick is escaped so it doesn't close the outer fence
        assertFalse(all.contains("\n```code```"),
            "inner fences must be escaped, got: " + all);
    }
}
