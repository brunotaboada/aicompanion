package io.aicompanion;

import java.io.IOException;
import java.nio.file.Path;

public class TestVerifier {

    private final String command;
    private final Path   projectDir;

    public TestVerifier(String command, Path projectDir) {
        this.command    = command;
        this.projectDir = projectDir;
    }

    public record Result(boolean passed, String output) {}

    public Result run() {
        if (command == null || command.isBlank()) {
            return new Result(true, "(no test command configured — skipping verification)");
        }
        try {
            var pb = new ProcessBuilder(command.split("\\s+"));
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);
            var proc = pb.start();
            String out  = new String(proc.getInputStream().readAllBytes());
            int    exit = proc.waitFor();
            return new Result(exit == 0, out);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "Test runner error: " + e.getMessage());
        }
    }
}
