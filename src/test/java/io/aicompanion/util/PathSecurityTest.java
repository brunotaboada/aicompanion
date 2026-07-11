package io.aicompanion.util;

import io.aicompanion.skill.SkillLoadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PathSecurityTest {

    @TempDir Path tempDir;

    @Test
    void rejectsTraversalInSegment() {
        assertThrows(IllegalArgumentException.class,
            () -> PathSecurity.validateSegment("../evil", "Feature name"));
    }

    @Test
    void rejectsTraversalInRelativePath() {
        assertThrows(SkillLoadException.class,
            () -> PathSecurity.validateRelativePath("../../../etc/passwd", "output"));
    }

    @Test
    void resolveContainedStaysInsideBase() {
        Path base = tempDir.resolve("features/auth");
        Path resolved = PathSecurity.resolveContained(base, "_prd.md", "output");
        assertTrue(resolved.startsWith(base.toAbsolutePath().normalize()));
    }
}
