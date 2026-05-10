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

    @Test
    void featuresModeOffWhenFeaturesDirMissing() {
        Config cfg = ConfigLoader.load(Map.of(
            "features_dir", tempDir.resolve("nope").toString(),
            "tasks_dir",    tempDir.toString()));
        assertFalse(new TaskRunner(cfg).featuresMode());
    }

    @Test
    void featuresModeOffWhenNoFeatureSubdirHasTasks() throws IOException {
        Path features = Files.createDirectory(tempDir.resolve("features"));
        Files.createDirectory(features.resolve("orphan-no-tasks"));
        Config cfg = ConfigLoader.load(Map.of(
            "features_dir", features.toString(),
            "tasks_dir",    tempDir.toString()));
        assertFalse(new TaskRunner(cfg).featuresMode());
    }

    @Test
    void resolveBatchesReturnsOneFeatureWithItsTasks() throws IOException {
        Path features = Files.createDirectory(tempDir.resolve("features"));
        Path tasks    = Files.createDirectories(features.resolve("hello/tasks"));
        Files.writeString(tasks.resolve("01-a.md"), "a");
        Files.writeString(tasks.resolve("02-b.md"), "b");

        Config cfg = ConfigLoader.load(Map.of(
            "features_dir", features.toString(),
            "tasks_dir",    tempDir.toString()));
        TaskRunner runner = new TaskRunner(cfg);
        assertTrue(runner.featuresMode());

        List<TaskRunner.Batch> batches = runner.resolveBatches();
        assertEquals(1, batches.size());
        assertEquals("hello", batches.get(0).featureName());
        assertEquals("hello/", batches.get(0).stateKeyPrefix());
        assertEquals(2, batches.get(0).taskPaths().size());
        assertEquals("01-a.md",
            batches.get(0).taskPaths().get(0).getFileName().toString());
    }

    @Test
    void resolveBatchesIteratesFeaturesAlphabetically() throws IOException {
        Path features = Files.createDirectory(tempDir.resolve("features"));
        Path zTasks   = Files.createDirectories(features.resolve("z-second/tasks"));
        Path aTasks   = Files.createDirectories(features.resolve("a-first/tasks"));
        Files.writeString(zTasks.resolve("01-z.md"), "z");
        Files.writeString(aTasks.resolve("01-a.md"), "a");
        // Feature dir without a tasks/ child must be skipped silently.
        Files.createDirectory(features.resolve("skip-me"));

        Config cfg = ConfigLoader.load(Map.of(
            "features_dir", features.toString(),
            "tasks_dir",    tempDir.toString()));
        List<TaskRunner.Batch> batches = new TaskRunner(cfg).resolveBatches();

        assertEquals(2, batches.size());
        assertEquals("a-first",  batches.get(0).featureName());
        assertEquals("z-second", batches.get(1).featureName());
    }

    @Test
    void singleModeBatchHasEmptyFeatureName() throws IOException {
        Files.writeString(tempDir.resolve("01-only.md"), "x");
        Config cfg = ConfigLoader.load(Map.of(
            "features_dir", tempDir.resolve("nope").toString(),
            "tasks_dir",    tempDir.toString()));
        List<TaskRunner.Batch> batches = new TaskRunner(cfg).resolveBatches();

        assertEquals(1, batches.size());
        assertEquals("", batches.get(0).featureName());
        assertEquals("", batches.get(0).stateKeyPrefix());
        assertEquals(1, batches.get(0).taskPaths().size());
    }
}
