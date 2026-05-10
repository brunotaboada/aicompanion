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

class ReporterTest {

    @TempDir
    Path tempDir;

    private Config configWithReportDir(Path dir) {
        return ConfigLoader.load(Map.of("report_dir", dir.toString()), Map.of());
    }

    @Test
    void taskWithoutFeaturePrefixLandsAtReportRoot() throws IOException {
        Path reports = tempDir.resolve("logs");
        new Reporter(configWithReportDir(reports)).write("01-task.md", "did stuff");

        List<Path> files = list(reports);
        assertEquals(1, files.size());
        assertEquals(reports, files.get(0).getParent(),
            "leaf-only task names must not create a subdirectory");
        String name = files.get(0).getFileName().toString();
        assertTrue(name.startsWith("01-task.md-"));
        assertTrue(name.endsWith(".md"));
    }

    @Test
    void featurePrefixBecomesSubdirectory() throws IOException {
        Path reports = tempDir.resolve("logs");
        new Reporter(configWithReportDir(reports)).write("auth/01-login.md", "summary");

        Path featureDir = reports.resolve("auth");
        assertTrue(Files.isDirectory(featureDir),
            "feature prefix should create <reportDir>/<feature>/");
        List<Path> files = list(featureDir);
        assertEquals(1, files.size());
        assertTrue(files.get(0).getFileName().toString().startsWith("01-login.md-"));
        assertTrue(Files.readString(files.get(0)).startsWith("# auth/01-login.md"),
            "header should keep the full feature/leaf path so logs are self-describing");
    }

    @Test
    void twoFeaturesGetSeparateSubdirectories() throws IOException {
        Path reports = tempDir.resolve("logs");
        Reporter r = new Reporter(configWithReportDir(reports));
        r.write("auth/01-login.md",       "a");
        r.write("billing/01-checkout.md", "b");

        assertTrue(Files.isDirectory(reports.resolve("auth")));
        assertTrue(Files.isDirectory(reports.resolve("billing")));
        assertEquals(1, list(reports.resolve("auth")).size());
        assertEquals(1, list(reports.resolve("billing")).size());
    }

    @Test
    void reportingDisabledWritesNothing() throws IOException {
        Path reports = tempDir.resolve("logs");
        Config cfg = ConfigLoader.load(Map.of(
            "report_dir",     reports.toString(),
            "report_enabled", "false"), Map.of());
        new Reporter(cfg).write("auth/01.md", "x");
        assertFalse(Files.exists(reports), "no report dir should be created when reporting is off");
    }

    private static List<Path> list(Path dir) throws IOException {
        try (var s = Files.list(dir)) { return s.toList(); }
    }
}
