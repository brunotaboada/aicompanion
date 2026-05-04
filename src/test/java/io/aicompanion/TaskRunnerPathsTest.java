package io.aicompanion;

import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskRunnerPathsTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveTaskPathsReturnsSortedMdAndTxtFiles() throws IOException {
        Files.writeString(tempDir.resolve("02-second.md"),  "task 2");
        Files.writeString(tempDir.resolve("01-first.md"),   "task 1");
        Files.writeString(tempDir.resolve("03-third.txt"),  "task 3");
        Files.writeString(tempDir.resolve("ignore.java"),   "not a task");

        Config cfg = ConfigLoader.load(Map.of("tasks_dir", tempDir.toString()));
        TaskRunner runner = new TaskRunner(cfg);
        List<Path> paths = runner.resolveTaskPaths();

        assertEquals(3, paths.size());
        assertEquals("01-first.md",  paths.get(0).getFileName().toString());
        assertEquals("02-second.md", paths.get(1).getFileName().toString());
        assertEquals("03-third.txt", paths.get(2).getFileName().toString());
    }

    @Test
    void emptyDirectoryReturnsEmptyList() throws IOException {
        Config cfg = ConfigLoader.load(Map.of("tasks_dir", tempDir.toString()));
        TaskRunner runner = new TaskRunner(cfg);
        assertTrue(runner.resolveTaskPaths().isEmpty());
    }

    @Test
    void missingDirectoryThrows() {
        Config cfg = ConfigLoader.load(Map.of("tasks_dir", "/nonexistent/path/xyz"));
        TaskRunner runner = new TaskRunner(cfg);
        assertThrows(IllegalArgumentException.class, runner::resolveTaskPaths);
    }
}
