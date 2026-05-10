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

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsAreAppliedWhenNoOverrides() {
        Config cfg = ConfigLoader.load(Map.of(), Map.of());
        assertEquals("features",      cfg.featuresDir());
        assertEquals("alphabetical",  cfg.taskSort());
        assertEquals(".",             cfg.projectDir());
        assertTrue(cfg.testEnabled());
        assertTrue(cfg.stopOnFailure());
        assertEquals(10,              cfg.sessionTimeoutMin());
        assertTrue(cfg.yolo());
        assertTrue(cfg.logToolCalls());
        assertFalse(cfg.logThoughts());
        assertEquals(List.of("md", "txt"), cfg.taskExtensions());
    }

    @Test
    void cliOverridesWinOverDefaults() {
        Config cfg = ConfigLoader.load(Map.of(
            "agent",        "gemini",
            "features_dir", "my-features",
            "test_enabled", "false",
            "yolo",         "false"
        ), Map.of());
        assertEquals("gemini",       cfg.agent());
        assertEquals("my-features",  cfg.featuresDir());
        assertFalse(cfg.testEnabled());
        assertFalse(cfg.yolo());
    }

    @Test
    void nullAgentWhenNotConfigured() {
        Config cfg = ConfigLoader.load(Map.of(), Map.of());
        assertNull(cfg.agent());
    }

    @Test
    void booleanParsing() {
        Config yes = ConfigLoader.load(Map.of("log_thoughts", "yes"), Map.of());
        assertTrue(yes.logThoughts());

        Config no = ConfigLoader.load(Map.of("log_thoughts", "false"), Map.of());
        assertFalse(no.logThoughts());
    }

    @Test
    void missingFileLoadsAsEmptyMap() {
        Map<String, Object> result = ConfigLoader.loadFileFrom(tempDir.resolve("does-not-exist.yml"));
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyFileLoadsAsEmptyMap() throws IOException {
        Path p = Files.writeString(tempDir.resolve("empty.yml"), "");
        assertTrue(ConfigLoader.loadFileFrom(p).isEmpty());
    }

    @Test
    void malformedYamlThrowsWithFilePathInMessage() throws IOException {
        Path p = Files.writeString(tempDir.resolve("bad.yml"), "agent: [unclosed\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadFileFrom(p));
        assertTrue(e.getMessage().contains("bad.yml"),
            "error should mention the offending file: " + e.getMessage());
        assertTrue(e.getMessage().contains("Could not parse"),
            "error should clearly state parse failure: " + e.getMessage());
    }

    @Test
    void nonMappingYamlThrows() throws IOException {
        Path p = Files.writeString(tempDir.resolve("list.yml"), "- foo\n- bar\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadFileFrom(p));
        assertTrue(e.getMessage().contains("YAML mapping"),
            "error should explain the structural mismatch: " + e.getMessage());
    }
}
