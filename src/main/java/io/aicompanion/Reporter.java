package io.aicompanion;

import io.aicompanion.config.Config;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Reporter {

    private static final DateTimeFormatter TS =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Config config;

    public Reporter(Config config) {
        this.config = config;
    }

    /**
     * Write a Markdown summary log for one task. {@code taskName} may include
     * a feature prefix (e.g. {@code "auth/01-login.md"}); the prefix becomes
     * a subdirectory under {@code reportDir} and only the leaf is slugified
     * into the filename, keeping logs browsable as features grow.
     */
    public void write(String taskName, String summary) {
        if (!config.reportEnabled() || config.reportDir() == null) return;
        try {
            int slash = taskName.lastIndexOf('/');
            String featurePart = slash >= 0 ? taskName.substring(0, slash) : "";
            String leaf        = slash >= 0 ? taskName.substring(slash + 1) : taskName;

            Path dir = Path.of(config.reportDir());
            if (!featurePart.isEmpty()) dir = dir.resolve(slug(featurePart));
            Files.createDirectories(dir);

            Path file = dir.resolve(slug(leaf) + "-" + LocalDateTime.now().format(TS) + ".md");
            Files.writeString(file, "# " + taskName + "\n\n" + summary + "\n");
            System.out.println("[log ] " + file);
        } catch (IOException e) {
            System.err.println("Warning: could not write report — " + e.getMessage());
        }
    }

    private static String slug(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "-");
    }
}
