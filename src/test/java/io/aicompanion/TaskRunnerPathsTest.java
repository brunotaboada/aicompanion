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
    void singleFeatureIteratesItsTasksInOrder() throws IOException {
        Path features = Files.createDirectory(tempDir.resolve("features"));
        Path tasks    = Files.createDirectories(features.resolve("hello/tasks"));
        Files.writeString(tasks.resolve("02-second.md"), "task 2");
        Files.writeString(tasks.resolve("01-first.md"),  "task 1");
        Files.writeString(tasks.resolve("03-third.txt"), "task 3");
        Files.writeString(tasks.resolve("ignore.java"),  "not a task");

        Config cfg = ConfigLoader.load(Map.of("features_dir", features.toString()));
        List<TaskRunner.Batch> batches = new TaskRunner(cfg).resolveBatches();

        assertEquals(1, batches.size(), "one feature, one batch");
        TaskRunner.Batch b = batches.get(0);
        assertEquals("hello",  b.featureName());
        assertEquals("hello/", b.stateKeyPrefix());
        assertEquals(3, b.taskPaths().size());
        assertEquals("01-first.md",  b.taskPaths().get(0).getFileName().toString());
        assertEquals("02-second.md", b.taskPaths().get(1).getFileName().toString());
        assertEquals("03-third.txt", b.taskPaths().get(2).getFileName().toString());
    }

    @Test
    void multipleFeaturesIterateAlphabetically() throws IOException {
        Path features = Files.createDirectory(tempDir.resolve("features"));
        Path zTasks   = Files.createDirectories(features.resolve("z-second/tasks"));
        Path aTasks   = Files.createDirectories(features.resolve("a-first/tasks"));
        Files.writeString(zTasks.resolve("01-z.md"), "z");
        Files.writeString(aTasks.resolve("01-a.md"), "a");

        Config cfg = ConfigLoader.load(Map.of("features_dir", features.toString()));
        List<TaskRunner.Batch> batches = new TaskRunner(cfg).resolveBatches();

        assertEquals(2, batches.size());
        assertEquals("a-first",  batches.get(0).featureName());
        assertEquals("z-second", batches.get(1).featureName());
    }

    @Test
    void featureWithoutTasksSubdirIsSkipped() throws IOException {
        Path features = Files.createDirectory(tempDir.resolve("features"));
        Files.createDirectory(features.resolve("orphan-no-tasks"));
        Path real = Files.createDirectories(features.resolve("real/tasks"));
        Files.writeString(real.resolve("01-only.md"), "x");

        Config cfg = ConfigLoader.load(Map.of("features_dir", features.toString()));
        List<TaskRunner.Batch> batches = new TaskRunner(cfg).resolveBatches();

        assertEquals(1, batches.size());
        assertEquals("real", batches.get(0).featureName());
    }

    @Test
    void featureWithEmptyTasksDirIsSkipped() throws IOException {
        Path features = Files.createDirectory(tempDir.resolve("features"));
        Files.createDirectories(features.resolve("empty/tasks"));
        Path real = Files.createDirectories(features.resolve("real/tasks"));
        Files.writeString(real.resolve("01-only.md"), "x");

        Config cfg = ConfigLoader.load(Map.of("features_dir", features.toString()));
        List<TaskRunner.Batch> batches = new TaskRunner(cfg).resolveBatches();

        assertEquals(1, batches.size());
        assertEquals("real", batches.get(0).featureName());
    }

    @Test
    void emptyFeaturesDirReturnsNoBatches() throws IOException {
        Path features = Files.createDirectory(tempDir.resolve("features"));
        Config cfg = ConfigLoader.load(Map.of("features_dir", features.toString()));
        assertTrue(new TaskRunner(cfg).resolveBatches().isEmpty());
    }

    @Test
    void missingFeaturesDirThrows() {
        Config cfg = ConfigLoader.load(Map.of("features_dir", "/nonexistent/path/xyz"));
        TaskRunner runner = new TaskRunner(cfg);
        assertThrows(IllegalArgumentException.class, runner::resolveBatches);
    }
}
