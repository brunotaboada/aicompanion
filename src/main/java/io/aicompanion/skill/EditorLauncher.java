package io.aicompanion.skill;

import java.io.IOException;
import java.nio.file.*;

/**
 * Opens {@code $EDITOR} (or {@code $VISUAL}, or vi) on a temporary file so the
 * user can compose a long answer instead of typing it into the single-line
 * {@code you>} prompt. Returns the file's contents on save, or null on cancel
 * (editor exited non-zero or returned an empty file).
 */
public class EditorLauncher {

    /** Functional hook so tests can swap the process spawn for a stub. */
    @FunctionalInterface
    public interface Spawner {
        int run(String[] cmd, Path file) throws IOException, InterruptedException;
    }

    private final Spawner spawner;

    public EditorLauncher() {
        this(EditorLauncher::defaultSpawn);
    }

    public EditorLauncher(Spawner spawner) {
        this.spawner = spawner;
    }

    /**
     * Open {@code $EDITOR} on a fresh tempfile, wait for it to exit, return the
     * contents. Empty content (user saved nothing) returns null so the chat
     * loop treats it as a cancel.
     */
    public String openAndRead() throws IOException, InterruptedException {
        String editor = resolveEditor();
        Path tmp = Files.createTempFile("aicompanion-input-", ".md");
        try {
            int exit = spawner.run(new String[]{editor, tmp.toString()}, tmp);
            if (exit != 0) return null;
            String content = Files.readString(tmp).trim();
            return content.isEmpty() ? null : content;
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private static String resolveEditor() {
        String e = System.getenv("VISUAL");
        if (e == null || e.isBlank()) e = System.getenv("EDITOR");
        if (e == null || e.isBlank()) e = "vi";
        return e;
    }

    private static int defaultSpawn(String[] cmd, Path file)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        return pb.start().waitFor();
    }
}
