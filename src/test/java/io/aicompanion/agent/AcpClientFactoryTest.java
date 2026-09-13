package io.aicompanion.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOptionKind;
import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AcpClientFactoryTest {

    @TempDir Path tmp;

    private static PermissionOption opt(String id, PermissionOptionKind kind) {
        return new PermissionOption(id, id, kind);
    }

    @Test
    void prefersAllowAlways() {
        String chosen = AcpClientFactory.chooseAllowOption(List.of(
            opt("once", PermissionOptionKind.ALLOW_ONCE),
            opt("always", PermissionOptionKind.ALLOW_ALWAYS)));
        assertEquals("always", chosen);
    }

    @Test
    void fallsBackToAllowOnceNotFirstOption() {
        String chosen = AcpClientFactory.chooseAllowOption(List.of(
            opt("reject", PermissionOptionKind.REJECT_ONCE),
            opt("once", PermissionOptionKind.ALLOW_ONCE)));
        assertEquals("once", chosen);
    }

    @Test
    void neverPicksARejectOption() {
        assertNull(AcpClientFactory.chooseAllowOption(List.of(
            opt("r1", PermissionOptionKind.REJECT_ONCE),
            opt("r2", PermissionOptionKind.REJECT_ALWAYS))));
    }

    @Test
    void writesInsideProjectOrFeaturesAreAllowed() {
        Path project  = tmp.resolve("proj");
        Path features = tmp.resolve("specs");
        Config cfg = ConfigLoader.load(Map.of(
            "project_dir", project.toString(), "features_dir", features.toString()), Map.of());
        assertDoesNotThrow(() -> AcpClientFactory.checkWritable(cfg,
            project.resolve("src/Main.java").toString()));
        assertDoesNotThrow(() -> AcpClientFactory.checkWritable(cfg,
            features.resolve("auth/_prd.md").toString()));
    }

    @Test
    void writesOutsideProjectAreRefused() {
        Path project = tmp.resolve("proj");
        Config cfg = ConfigLoader.load(Map.of(
            "project_dir", project.toString(), "features_dir", project.resolve("features").toString()),
            Map.of());
        assertThrows(IllegalArgumentException.class, () -> AcpClientFactory.checkWritable(cfg,
            tmp.resolve("elsewhere.txt").toString()));
        // A ".." hop out of the project is caught after normalisation.
        assertThrows(IllegalArgumentException.class, () -> AcpClientFactory.checkWritable(cfg,
            project.resolve("../escape.txt").toString()));
    }
}
