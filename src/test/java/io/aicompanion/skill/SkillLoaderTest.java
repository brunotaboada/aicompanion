package io.aicompanion.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    @TempDir Path tempDir;

    // ── discover ─────────────────────────────────────────────────────────────

    @Test
    void discoverReturnsEmptyListWhenSkillsRootMissing() throws IOException {
        SkillLoader loader = new SkillLoader(tempDir.resolve("does-not-exist"));
        assertEquals(List.of(), loader.discover());
    }

    @Test
    void discoverReturnsEmptyListWhenSkillsRootEmpty() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("skills"));
        assertEquals(List.of(), new SkillLoader(root).discover());
    }

    @Test
    void discoverIgnoresDirectoriesWithoutSkillMd() throws IOException {
        Path root = tempDir.resolve("skills");
        writeSkill(root, "valid", "valid skill", "_out.md", "body");
        Files.createDirectories(root.resolve("not-a-skill"));   // no SKILL.md inside

        assertEquals(List.of("valid"), new SkillLoader(root).discover());
    }

    @Test
    void discoverReturnsNamesSortedAlphabetically() throws IOException {
        Path root = tempDir.resolve("skills");
        writeSkill(root, "create-tasks",     "tasks",     "_tasks.md",    "body");
        writeSkill(root, "create-prd",       "prd",       "_prd.md",      "body");
        writeSkill(root, "create-tech-spec", "techspec",  "_techspec.md", "body");

        assertEquals(
            List.of("create-prd", "create-tasks", "create-tech-spec"),
            new SkillLoader(root).discover());
    }

    // ── describe ─────────────────────────────────────────────────────────────

    @Test
    void describeParsesFrontmatter() throws IOException {
        Path root = tempDir.resolve("skills");
        writeSkill(root, "create-prd", "Make a PRD interactively", "_prd.md", "body text");

        SkillMetadata md = new SkillLoader(root).describe("create-prd");
        assertEquals("create-prd",                md.name());
        assertEquals("Make a PRD interactively",  md.description());
        assertEquals("_prd.md",                   md.outputRelativePath());
    }

    @Test
    void describeThrowsClearErrorWhenSkillMissing() {
        SkillLoader loader = new SkillLoader(tempDir);
        SkillLoadException e = assertThrows(SkillLoadException.class,
            () -> loader.describe("nope"));
        assertTrue(e.getMessage().contains("nope"),
            "error should name the skill: " + e.getMessage());
        assertTrue(e.getMessage().contains("init skills"),
            "error should hint at the scaffolder: " + e.getMessage());
    }

    @Test
    void describeThrowsWhenFrontmatterMissing() throws IOException {
        Path root = tempDir.resolve("skills");
        Path dir  = Files.createDirectories(root.resolve("broken"));
        Files.writeString(dir.resolve("SKILL.md"), "# no frontmatter here\nbody");

        SkillLoadException e = assertThrows(SkillLoadException.class,
            () -> new SkillLoader(root).describe("broken"));
        assertTrue(e.getMessage().contains("frontmatter"),
            "error should explain the missing frontmatter: " + e.getMessage());
    }

    @Test
    void describeThrowsWhenRequiredFieldMissing() throws IOException {
        Path root = tempDir.resolve("skills");
        Path dir  = Files.createDirectories(root.resolve("partial"));
        Files.writeString(dir.resolve("SKILL.md"),
            "---\ndescription: only this\n---\nbody");

        SkillLoadException e = assertThrows(SkillLoadException.class,
            () -> new SkillLoader(root).describe("partial"));
        assertTrue(e.getMessage().contains("output"),
            "error should name the missing field: " + e.getMessage());
    }

    @Test
    void describeThrowsWhenFrontmatterUnterminated() throws IOException {
        Path root = tempDir.resolve("skills");
        Path dir  = Files.createDirectories(root.resolve("unterminated"));
        Files.writeString(dir.resolve("SKILL.md"),
            "---\ndescription: x\noutput: _out.md\nbody-with-no-closing-fence");

        SkillLoadException e = assertThrows(SkillLoadException.class,
            () -> new SkillLoader(root).describe("unterminated"));
        assertTrue(e.getMessage().contains("unterminated"),
            "error should explain the unterminated block: " + e.getMessage());
    }

    // ── load + render ────────────────────────────────────────────────────────

    @Test
    void loadSubstitutesEveryPlaceholder() throws IOException {
        Path root = tempDir.resolve("skills");
        String body = """
            Feature: {{feature}}
            Dir: {{feature_dir}}
            Output: {{output_file}}
            ADRs: {{adr_dir}}
            Seed: {{seed_file}}
            Update: {{update_mode}}
            Templates: {{templates_dir}}
            Shared: {{shared_templates_dir}}""";
        writeSkill(root, "create-prd", "desc", "_prd.md", body);

        Path featureDir = tempDir.resolve("features/auth");
        SkillContext ctx = new SkillContext("auth", featureDir, null, false);

        Skill skill = new SkillLoader(root).load("create-prd", ctx);

        String out = skill.body();
        assertTrue(out.contains("Feature: auth"),                                       out);
        assertTrue(out.contains("Dir: " + posix(featureDir) + "/"),                     out);
        assertTrue(out.contains("Output: " + posix(featureDir.resolve("_prd.md"))),     out);
        assertTrue(out.contains("ADRs: " + posix(featureDir.resolve("adrs")) + "/"),    out);
        assertTrue(out.contains("Seed: " + posix(featureDir.resolve("_idea.md"))),      out);
        assertTrue(out.contains("Update: no"),                                          out);
        assertTrue(out.contains("Templates: " + posix(root.resolve("create-prd/references")) + "/"), out);
        assertTrue(out.contains("Shared: " + posix(root.resolve("_shared/references")) + "/"), out);

        assertEquals(featureDir.resolve("_prd.md"), skill.outputFile());
    }

    @Test
    void loadStripsTheFrontmatterFromTheBody() throws IOException {
        Path root = tempDir.resolve("skills");
        writeSkill(root, "create-prd", "desc", "_prd.md", "# Heading\nbody line");

        Skill skill = new SkillLoader(root)
            .load("create-prd", new SkillContext("auth", tempDir.resolve("features/auth"), null, false));

        assertFalse(skill.body().contains("---"),       "body must not contain frontmatter delimiter");
        assertFalse(skill.body().contains("description"), "body must not contain frontmatter fields");
        assertTrue(skill.body().startsWith("# Heading"), "body should start with the first content line");
    }

    @Test
    void loadHonoursUpdateModeFlag() throws IOException {
        Path root = tempDir.resolve("skills");
        writeSkill(root, "s", "desc", "_out.md", "{{update_mode}}");

        Skill yes = new SkillLoader(root).load("s",
            new SkillContext("f", tempDir.resolve("features/f"), null, true));
        Skill no  = new SkillLoader(root).load("s",
            new SkillContext("f", tempDir.resolve("features/f"), null, false));

        assertEquals("yes", yes.body().trim());
        assertEquals("no",  no.body().trim());
    }

    @Test
    void loadUsesExplicitSeedFileWhenProvided() throws IOException {
        Path root = tempDir.resolve("skills");
        writeSkill(root, "s", "desc", "_out.md", "{{seed_file}}");
        Path customSeed = tempDir.resolve("notes/rfc.md");

        Skill skill = new SkillLoader(root).load("s",
            new SkillContext("f", tempDir.resolve("features/f"), customSeed, false));

        assertEquals(posix(customSeed), skill.body().trim());
    }

    @Test
    void loadProducesAbsoluteOutputFileUnderFeatureDir() throws IOException {
        Path root = tempDir.resolve("skills");
        writeSkill(root, "create-prd", "desc", "_prd.md", "body");
        Path featureDir = tempDir.resolve("features/checkout");

        Skill skill = new SkillLoader(root).load("create-prd",
            new SkillContext("checkout", featureDir, null, false));

        assertEquals(featureDir.resolve("_prd.md"), skill.outputFile());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void writeSkill(Path root, String name, String desc,
                                    String output, String body) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        String content = "---\ndescription: " + desc + "\noutput: " + output + "\n---\n" + body;
        Files.writeString(dir.resolve("SKILL.md"), content);
    }

    private static String posix(Path p) {
        return p.toString().replace('\\', '/');
    }
}
