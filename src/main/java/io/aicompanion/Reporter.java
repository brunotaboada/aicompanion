package io.aicompanion;

import io.aicompanion.config.Config;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Reporter {

    private final Config config;

    public Reporter(Config config) {
        this.config = config;
    }

    public void write(String taskName, String summary) {
        if (!config.reportEnabled() || config.reportDir() == null) return;
        try {
            Path dir = Path.of(config.reportDir());
            Files.createDirectories(dir);
            String ts   = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String slug = taskName.replaceAll("[^a-zA-Z0-9._-]", "-");
            Path   file = dir.resolve(slug + "-" + ts + ".md");
            Files.writeString(file, "# " + taskName + "\n\n" + summary + "\n");
            System.out.println("[log ] " + file);
        } catch (IOException e) {
            System.err.println("Warning: could not write report — " + e.getMessage());
        }
    }
}
