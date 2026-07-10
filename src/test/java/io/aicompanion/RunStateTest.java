package io.aicompanion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RunStateTest {

    @TempDir
    Path tempDir;

    private Path stateFile() { return tempDir.resolve("state.yml"); }

    @Test
    void loadMissingFileReturnsEmptyState() {
        RunState s = RunState.load(stateFile());
        assertEquals(0, s.passedCount());
        assertEquals(0, s.failedCount());
        assertNull(s.get("anything.md"));
    }

    @Test
    void roundTripSaveAndLoad() throws IOException {
        RunState s = RunState.load(stateFile());
        s.setFeaturesDir("features");
        s.markPassed("01-a.md", "hash-a");
        s.markFailed("02-b.md", "hash-b");
        s.save();

        RunState reloaded = RunState.load(stateFile());
        assertEquals(1, reloaded.passedCount());
        assertEquals(1, reloaded.failedCount());
        assertEquals(RunState.Status.PASSED, reloaded.get("01-a.md").status());
        assertEquals("hash-a",                reloaded.get("01-a.md").hash());
        assertEquals(RunState.Status.FAILED, reloaded.get("02-b.md").status());
    }

    @Test
    void saveLeavesNoTempFileAndOverwritesExistingState() throws IOException {
        RunState s = RunState.load(stateFile());
        s.markPassed("01-a.md", "h1");
        s.save();
        s.markFailed("01-a.md", "h1");
        s.save();   // second save must atomically replace the first

        try (var files = Files.list(tempDir)) {
            assertEquals(java.util.List.of(stateFile()), files.toList(),
                "save must not leave .tmp files behind");
        }
        assertEquals(RunState.Status.FAILED, RunState.load(stateFile()).get("01-a.md").status());
    }

    @Test
    void corruptFileTreatedAsEmptyWithWarning() throws IOException {
        Files.writeString(stateFile(), ":::: not yaml ::: [unbalanced");
        RunState s = RunState.load(stateFile());
        assertEquals(0, s.passedCount());
        assertEquals(0, s.failedCount());
        assertNotNull(s.loadWarning());
        assertTrue(s.loadWarning().contains("WARNING"));
    }

    @Test
    void featuresDirMismatchInvalidatesResume() {
        RunState s = RunState.load(stateFile());
        s.setFeaturesDir("old-features");
        s.markPassed("a.md", "h1");
        assertFalse(s.isResumeValidFor("new-features"));
        s.clearTasks();
        assertFalse(s.shouldSkip("a.md", "h1", false));
    }

    @Test
    void storedFeaturesDirRoundTrips() throws IOException {
        RunState s = RunState.load(stateFile());
        s.setFeaturesDir("features");
        s.markPassed("a.md", "h");
        s.save();
        assertEquals("features", RunState.load(stateFile()).storedFeaturesDir());
    }

    @Test
    void shouldSkipPassedWithMatchingHash() {
        RunState s = RunState.load(stateFile());
        s.markPassed("01-a.md", "h1");
        assertTrue(s.shouldSkip("01-a.md", "h1", false));
        assertTrue(s.shouldSkip("01-a.md", "h1", true));   // retry-failed irrelevant for PASSED
    }

    @Test
    void hashMismatchInvalidatesSkip() {
        RunState s = RunState.load(stateFile());
        s.markPassed("01-a.md", "h1");
        assertFalse(s.shouldSkip("01-a.md", "h2-edited", false));
    }

    @Test
    void failedSkippedByDefaultButNotWithRetryFailed() {
        RunState s = RunState.load(stateFile());
        s.markFailed("02-b.md", "h2");
        assertTrue(s.shouldSkip("02-b.md", "h2", false));   // default resume: skip failed
        assertFalse(s.shouldSkip("02-b.md", "h2", true));   // --retry-failed: re-run
    }

    @Test
    void unknownTaskNeverSkipped() {
        RunState s = RunState.load(stateFile());
        assertFalse(s.shouldSkip("never-seen.md", "h", false));
        assertFalse(s.shouldSkip("never-seen.md", "h", true));
    }

    @Test
    void labelForReportsCorrectStatus() {
        RunState s = RunState.load(stateFile());
        s.markPassed("a.md", "h1");
        s.markFailed("b.md", "h2");
        assertEquals("passed",  s.labelFor("a.md", "h1"));
        assertEquals("edited",  s.labelFor("a.md", "h-different"));
        assertEquals("failed",  s.labelFor("b.md", "h2"));
        assertEquals("pending", s.labelFor("c.md", "h3"));
    }

    @Test
    void hashIsDeterministicAndDistinct() {
        assertEquals(RunState.hash("hello"),  RunState.hash("hello"));
        assertNotEquals(RunState.hash("hello"), RunState.hash("hello "));
        // SHA-256 hex is 64 chars
        assertEquals(64, RunState.hash("anything").length());
    }

    @Test
    void deleteRemovesFile() throws IOException {
        RunState s = RunState.load(stateFile());
        s.markPassed("a.md", "h");
        s.save();
        assertTrue(Files.exists(stateFile()));

        RunState.delete(stateFile());
        assertFalse(Files.exists(stateFile()));

        // No-op when already gone
        RunState.delete(stateFile());
    }

    @Test
    void markPassedOverwritesPreviousFailure() {
        RunState s = RunState.load(stateFile());
        s.markFailed("a.md", "h1");
        s.markPassed("a.md", "h1");
        assertEquals(RunState.Status.PASSED, s.get("a.md").status());
        assertEquals(1, s.passedCount());
        assertEquals(0, s.failedCount());
    }
}
