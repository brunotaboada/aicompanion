package io.aicompanion;

import io.aicompanion.util.Platform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TestVerifier {

    private final List<String> commands;
    private final Path         projectDir;
    private final long         timeoutMillis;

    public TestVerifier(String command, Path projectDir) {
        this(command, projectDir, 0);
    }

    /** @param timeoutMin kill each command after this many minutes; {@code <= 0} = no limit */
    public TestVerifier(String command, Path projectDir, int timeoutMin) {
        this(toList(command), projectDir, timeoutMin * 60_000L);
    }

    /** @param commands verification commands, run in order; the first failure stops the run */
    public TestVerifier(List<String> commands, Path projectDir, int timeoutMin) {
        this(commands, projectDir, timeoutMin * 60_000L);
    }

    /** Millisecond-granular variant so tests can exercise the kill path quickly. */
    TestVerifier(String command, Path projectDir, long timeoutMillis) {
        this(toList(command), projectDir, timeoutMillis);
    }

    TestVerifier(List<String> commands, Path projectDir, long timeoutMillis) {
        this.commands      = commands;
        this.projectDir    = projectDir;
        this.timeoutMillis = timeoutMillis;
    }

    private static List<String> toList(String command) {
        return command == null || command.isBlank() ? List.of() : List.of(command);
    }

    /** @param command the command this result came from; {@code null} when nothing ran */
    public record Result(boolean passed, String output, String command) {
        public Result(boolean passed, String output) {
            this(passed, output, null);
        }
    }

    /**
     * Run every command in order. The first non-zero exit (or timeout) stops
     * the run and its result — output plus the offending command — is
     * returned, so fix prompts can name what actually failed.
     */
    public Result run() {
        if (commands.isEmpty()) {
            return new Result(true, "(no test command configured — skipping verification)");
        }
        StringBuilder combined = new StringBuilder();
        for (String command : commands) {
            Result result = runOne(command);
            if (!result.passed()) return result;
            if (commands.size() > 1) combined.append("$ ").append(command).append('\n');
            combined.append(result.output());
        }
        return new Result(true, combined.toString());
    }

    private Result runOne(String command) {
        Process proc = null;
        try {
            var pb = new ProcessBuilder(buildArgv(command));
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);
            proc = pb.start();

            // Drain stdout on a separate thread so waitFor can time out even
            // while the child is still producing output — readAllBytes on this
            // thread would block until the pipe closes and defeat the timeout.
            var    buf   = new ByteArrayOutputStream();
            var    in    = proc.getInputStream();
            Thread drain = new Thread(() -> {
                try { in.transferTo(buf); } catch (IOException ignored) {}
            }, "test-output-drain");
            drain.setDaemon(true);
            drain.start();

            boolean finished;
            if (timeoutMillis > 0) {
                finished = proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                proc.waitFor();
                finished = true;
            }

            if (!finished) {
                destroyTree(proc);
                drain.join(2_000);
                return new Result(false,
                    "Test command timed out after " + humanTimeout() + ": `" + command + "`\n"
                    + "The process was killed. Partial output:\n" + buf.toString(), command);
            }

            // The pipe can stay open past process exit when a grandchild
            // inherited stdout (e.g. a dev server the tests left running), so
            // bound the wait for the drain thread rather than joining forever.
            drain.join(10_000);
            return new Result(proc.exitValue() == 0, buf.toString(), command);
        } catch (IOException e) {
            return new Result(false, "Test runner error: " + e.getMessage(), command);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) destroyTree(proc);
            return new Result(false, "Test run interrupted: " + e.getMessage(), command);
        }
    }

    private String humanTimeout() {
        return timeoutMillis % 60_000 == 0
            ? (timeoutMillis / 60_000) + " minute(s)"
            : (timeoutMillis / 1_000.0) + " second(s)";
    }

    /** Kill the process and all its descendants: polite destroy, short grace, then forcible. */
    private static void destroyTree(Process proc) {
        proc.descendants().forEach(ProcessHandle::destroy);
        proc.destroy();
        try {
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
        }
    }

    /**
     * `shell:<script>` runs the rest through PowerShell on Windows (falling back
     * to cmd) or /bin/sh on Unix, so users can use pipes, &&, env expansion, etc.
     * Anything else is tokenized (quotes respected) and exec'd directly.
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
        return tokenize(command);
    }

    /**
     * Split a command line into argv, honouring single and double quotes so
     * arguments with spaces survive (e.g. {@code mvn test -Dtest="Foo Bar"}).
     * Quotes are stripped from the tokens; an unclosed quote runs to the end
     * of the string rather than failing.
     */
    static List<String> tokenize(String command) {
        List<String> argv = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false, hasToken = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') inSingle = false;
                else cur.append(c);
            } else if (inDouble) {
                if (c == '"') inDouble = false;
                else cur.append(c);
            } else if (c == '\'') {
                inSingle = true;
                hasToken = true;
            } else if (c == '"') {
                inDouble = true;
                hasToken = true;
            } else if (Character.isWhitespace(c)) {
                if (hasToken || cur.length() > 0) {
                    argv.add(cur.toString());
                    cur.setLength(0);
                    hasToken = false;
                }
            } else {
                cur.append(c);
            }
        }
        if (hasToken || cur.length() > 0) argv.add(cur.toString());
        return argv;
    }
}
