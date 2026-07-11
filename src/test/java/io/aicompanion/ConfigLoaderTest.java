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
    void verifyCommandsDefaultToEmptyAndFallBackToTestCommand() {
        Config cfg = ConfigLoader.load(Map.of("test_command", "mvn test -q"), Map.of());
        assertTrue(cfg.verifyCommands().isEmpty());
        assertEquals(List.of("mvn test -q"), cfg.effectiveVerifyCommands());
    }

    @Test
    void verifyCommandsFromFileListOverrideTestCommand() {
        Config cfg = ConfigLoader.load(Map.of("test_command", "mvn test -q"),
            Map.of("verify_commands", List.of("npm run lint", "npm test")));
        assertEquals(List.of("npm run lint", "npm test"), cfg.effectiveVerifyCommands());
    }

    @Test
    void verifyCommandsFromCliAreCommaSplitAndTrimmed() {
        Config cfg = ConfigLoader.load(
            Map.of("verify_commands", "npm run lint, npm test"), Map.of());
        assertEquals(List.of("npm run lint", "npm test"), cfg.effectiveVerifyCommands());
    }

    @Test
    void effectiveVerifyCommandsEmptyWhenNothingConfigured() {
        Config cfg = ConfigLoader.load(Map.of("test_command", ""), Map.of());
        assertTrue(cfg.effectiveVerifyCommands().isEmpty());
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

    @Test
    void tokenSavingDefaultsArePreserved() {
        Config cfg = ConfigLoader.load(Map.of(), Map.of());
        assertEquals(200, cfg.fixOutputMaxLines());
        assertFalse(cfg.taskPreambleStrip());
        assertEquals(0,   cfg.compactAfterNTasks());
        assertFalse(cfg.preCheckTests());
        assertEquals(0,   cfg.maxTokensPerRun());
        assertFalse(cfg.initInstructions());
    }

    @Test
    void tokenSavingOverridesApply() {
        Config cfg = ConfigLoader.load(Map.of(
            "fix_output_max_lines",   "50",
            "task_preamble_strip",    "true",
            "compact_after_n_tasks",  "5",
            "pre_check_tests",        "true",
            "max_tokens_per_run",     "100000",
            "init_instructions",      "true"
        ), Map.of());
        assertEquals(50,     cfg.fixOutputMaxLines());
        assertTrue(cfg.taskPreambleStrip());
        assertEquals(5,      cfg.compactAfterNTasks());
        assertTrue(cfg.preCheckTests());
        assertEquals(100000, cfg.maxTokensPerRun());
        assertTrue(cfg.initInstructions());
    }

    @Test
    void skillsMapIsEmptyByDefault() {
        Config cfg = ConfigLoader.load(Map.of(), Map.of());
        assertNotNull(cfg.skills());
        assertTrue(cfg.skills().isEmpty());
    }

    @Test
    void skillsFromYamlAreParsed() {
        Map<String, Object> file = Map.of(
            "skills", Map.of(
                "create-prd",       Map.of("model", "opus"),
                "create-tech-spec", Map.of("model", "opus"),
                "create-tasks",     Map.of("model", "sonnet")
            )
        );
        Config cfg = ConfigLoader.load(Map.of(), file);
        assertEquals("opus",   cfg.skills().get("create-prd").model());
        assertEquals("opus",   cfg.skills().get("create-tech-spec").model());
        assertEquals("sonnet", cfg.skills().get("create-tasks").model());
    }

    @Test
    void envVarOverridesSkillModelFromFile() {
        Map<String, Object> file = Map.of(
            "skills", Map.of("create-prd", Map.of("model", "sonnet"))
        );
        Map<String, String> env = Map.of(
            "AICOMPANION_SKILLS_CREATE_PRD_MODEL", "opus"
        );
        Config cfg = ConfigLoader.load(Map.of(), file, env);
        assertEquals("opus", cfg.skills().get("create-prd").model());
    }

    @Test
    void envVarAddsSkillWhenNoneInFile() {
        Map<String, String> env = Map.of(
            "AICOMPANION_SKILLS_CREATE_TASKS_MODEL", "haiku"
        );
        Config cfg = ConfigLoader.load(Map.of(), Map.of(), env);
        assertEquals("haiku", cfg.skills().get("create-tasks").model());
    }

    @Test
    void modelForUsesSkillOverrideWhenPresent() {
        Map<String, Object> file = Map.of(
            "model", "sonnet",
            "skills", Map.of("create-prd", Map.of("model", "opus"))
        );
        Config cfg = ConfigLoader.load(Map.of(), file);
        assertEquals("opus",   cfg.modelFor("create-prd"));
        assertEquals("sonnet", cfg.modelFor("create-tasks"));   // falls back to global
    }

    @Test
    void modelForFallsBackToGlobalWhenSkillUnconfigured() {
        Config cfg = ConfigLoader.load(Map.of("model", "sonnet"), Map.of());
        assertEquals("sonnet", cfg.modelFor("anything"));
    }

    @Test
    void modelForReturnsNullWhenNothingSet() {
        Config cfg = ConfigLoader.load(Map.of(), Map.of());
        assertNull(cfg.modelFor("create-prd"));
    }

    @Test
    void modelForFallsBackToGlobalWhenSkillEntryHasBlankModel() {
        Map<String, Object> file = Map.of(
            "model", "sonnet",
            "skills", Map.of("create-prd", Map.of("model", ""))
        );
        Config cfg = ConfigLoader.load(Map.of(), file);
        assertEquals("sonnet", cfg.modelFor("create-prd"));
    }

    @Test
    void testCommandAutoDetectUsesProjectDirNotCwd() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("repo"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");

        Config cfg = ConfigLoader.load(Map.of("project_dir", project.toString()), Map.of());
        assertEquals("mvn test -q", cfg.testCommand());
    }

    @Test
    void testCommandAutoDetectReturnsNullWhenProjectHasNoMarkers() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("empty-repo"));

        Config cfg = ConfigLoader.load(Map.of("project_dir", project.toString()), Map.of());
        assertNull(cfg.testCommand());
    }

    @Test
    void explicitTestCommandWinsOverProjectDirAutoDetect() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("repo"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");

        Config cfg = ConfigLoader.load(Map.of(
            "project_dir",   project.toString(),
            "test_command",  "make check"
        ), Map.of());
        assertEquals("make check", cfg.testCommand());
    }

    @Test
    void reuseSessionDefaultsToTrue() {
        Config cfg = ConfigLoader.load(Map.of(), Map.of());
        assertTrue(cfg.reuseSession());
    }

    @Test
    void reuseSessionCanBeDisabled() {
        Config cfg = ConfigLoader.load(Map.of("reuse_session", "false"), Map.of());
        assertFalse(cfg.reuseSession());
    }
}
