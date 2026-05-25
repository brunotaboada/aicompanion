package io.aicompanion.skill;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SkillCommandArgsTest {

    @Test
    void parsesFeatureOnly() {
        SkillCommandArgs a = SkillCommandArgs.parse(new String[]{"create-prd", "auth"}, 1);
        assertEquals("auth", a.feature());
        assertNull(a.seed());
        assertNull(a.model());
    }

    @Test
    void parsesAllFlagsAndFeatureInAnyOrder() {
        SkillCommandArgs a = SkillCommandArgs.parse(
            new String[]{"create-prd", "--seed", "notes.md", "auth", "--model", "opus"}, 1);
        assertEquals("auth",            a.feature());
        assertEquals(Path.of("notes.md"), a.seed());
        assertEquals("opus",            a.model());
    }

    @Test
    void featureIsNullWhenNotProvided() {
        SkillCommandArgs a = SkillCommandArgs.parse(
            new String[]{"create-prd", "--model", "opus"}, 1);
        assertNull(a.feature());
        assertEquals("opus", a.model());
    }

    @Test
    void unknownFlagsAreIgnored() {
        SkillCommandArgs a = SkillCommandArgs.parse(
            new String[]{"create-prd", "auth", "--unrecognised", "x"}, 1);
        assertEquals("auth", a.feature());
    }
}
