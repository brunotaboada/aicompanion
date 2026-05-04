package io.aicompanion;

import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @Test
    void defaultsAreAppliedWhenNoOverrides() {
        Config cfg = ConfigLoader.load(Map.of());
        assertEquals("feature/tasks", cfg.tasksDir());
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
            "agent",       "gemini",
            "tasks_dir",   "my-tasks",
            "test_enabled","false",
            "yolo",        "false"
        ));
        assertEquals("gemini",    cfg.agent());
        assertEquals("my-tasks",  cfg.tasksDir());
        assertFalse(cfg.testEnabled());
        assertFalse(cfg.yolo());
    }

    @Test
    void nullAgentWhenNotConfigured() {
        Config cfg = ConfigLoader.load(Map.of());
        assertNull(cfg.agent());
    }

    @Test
    void booleanParsing() {
        Config yes = ConfigLoader.load(Map.of("log_thoughts", "yes"));
        assertTrue(yes.logThoughts());

        Config no = ConfigLoader.load(Map.of("log_thoughts", "false"));
        assertFalse(no.logThoughts());
    }
}
