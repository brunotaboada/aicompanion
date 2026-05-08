package io.aicompanion;

import io.aicompanion.util.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
            var pb = new ProcessBuilder(buildArgv(command));
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

    /**
     * `shell:<script>` runs the rest through PowerShell on Windows (falling back
     * to cmd) or /bin/sh on Unix, so users can use pipes, &&, env expansion, etc.
     * Anything else is whitespace-split and exec'd directly.
     */
    static List<String> buildArgv(String command) {
        if (command.startsWith("shell:")) {
            String script = command.substring("shell:".length()).trim();
            if (Platform.isWindows()) {
                if (Platform.findOnPath("pwsh") != null)
                    return List.of("pwsh", "-NoProfile", "-Command", script);
                if (Platform.findOnPath("powershell") != null)
                    return List.of("powershell", "-NoProfile", "-Command", script);
                return List.of("cmd", "/c", script);
            }
            return List.of("/bin/sh", "-c", script);
        }
        return List.of(command.split("\\s+"));
    }
}
