package io.aicompanion.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SkillScaffolderTest {

    @TempDir Path tempDir;

    // ── copyTree (the directly testable internal) ────────────────────────────

    @Test
    void copyTreeCopiesEverySkillAndItsReferences() throws IOException {
        Path src = tempDir.resolve("src/skills");
        Files.createDirectories(src.resolve("create-prd/references"));
        Files.writeString(src.resolve("create-prd/SKILL.md"), "---\noutput: _prd.md\n---\n# prd");
        Files.writeString(src.resolve("create-prd/references/template.md"), "template");

        Path target = tempDir.resolve("project/.agents/skills");
        SkillScaffolder.Result r = SkillScaffolder.copyTree(src, target, false);

        assertEquals(List.of("create-prd"), r.created());
        assertEquals(List.of(),             r.skipped());
        assertTrue(Files.exists(target.resolve("create-prd/SKILL.md")));
        assertTrue(Files.exists(target.resolve("create-prd/references/template.md")));
        assertEquals("template",
            Files.readString(target.resolve("create-prd/references/template.md")));
    }

    @Test
    void copyTreeSkipsExistingDirectoriesWhenNotForced() throws IOException {
        Path src = tempDir.resolve("src/skills");
        writeFakeSkill(src, "create-prd",       "_prd.md");
        writeFakeSkill(src, "create-tech-spec", "_techspec.md");

        Path target = tempDir.resolve("project/.agents/skills");
        // Pre-create one of the destinations
        Files.createDirectories(target.resolve("create-prd"));
        Files.writeString(target.resolve("create-prd/SKILL.md"), "user customised");

        SkillScaffolder.Result r = SkillScaffolder.copyTree(src, target, /*force*/ false);

        assertEquals(List.of("create-tech-spec"), r.created());
        assertEquals(List.of("create-prd"),       r.skipped());
        // user's existing file must be untouched
        assertEquals("user customised",
            Files.readString(target.resolve("create-prd/SKILL.md")));
    }

    @Test
    void copyTreeOverwritesExistingWhenForced() throws IOException {
        Path src = tempDir.resolve("src/skills");
        writeFakeSkill(src, "create-prd", "_prd.md");

        Path target = tempDir.resolve("project/.agents/skills");
        Files.createDirectories(target.resolve("create-prd"));
        Files.writeString(target.resolve("create-prd/SKILL.md"), "old content");

        SkillScaffolder.Result r = SkillScaffolder.copyTree(src, target, /*force*/ true);

        assertEquals(List.of("create-prd"), r.created());
        assertEquals(List.of(),             r.skipped());
        assertTrue(Files.readString(target.resolve("create-prd/SKILL.md")).contains("---"),
            "force should overwrite — expected the scaffolded SKILL.md, got the old one");
    }

    @Test
    void resultIsEmptyWhenSourceHasNoSkillDirs() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("empty"));
        Path target = tempDir.resolve("project/.agents/skills");

        SkillScaffolder.Result r = SkillScaffolder.copyTree(src, target, false);

        assertTrue(r.isEmpty());
    }

    // ── scaffold() end-to-end via the test classpath ─────────────────────────

    @Test
    void scaffoldCopiesTheBundledThreeSkillsToTheProjectRoot() throws Exception {
        // The Maven build maps .agents/skills/ → target/classes/skills/, so the
        // test classpath has them — same code path production uses from a JAR.
        Path projectRoot = tempDir.resolve("fresh-project");
        SkillScaffolder.Result r = new SkillScaffolder().scaffold(projectRoot, false);

        assertTrue(r.created().contains("create-prd"),
            "expected create-prd in result: " + r.created());
        assertTrue(r.created().contains("create-tech-spec"),
            "expected create-tech-spec in result: " + r.created());
        assertTrue(r.created().contains("create-tasks"),
            "expected create-tasks in result: " + r.created());
        assertTrue(r.created().contains("_shared"),
            "expected _shared in result: " + r.created());

        // Every skill arrives with at least its SKILL.md; _shared is references only
        for (String name : r.created()) {
            if ("_shared".equals(name)) {
                assertTrue(Files.exists(projectRoot.resolve(
                    ".agents/skills/_shared/references/question-protocol.md")));
                continue;
            }
            assertTrue(Files.exists(projectRoot.resolve(".agents/skills/" + name + "/SKILL.md")),
                "SKILL.md missing for " + name);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void writeFakeSkill(Path root, String name, String output) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("SKILL.md"),
            "---\ndescription: " + name + "\noutput: " + output + "\n---\nbody");
    }
}
