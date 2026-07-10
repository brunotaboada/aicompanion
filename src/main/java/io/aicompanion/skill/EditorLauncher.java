package io.aicompanion.skill;

import io.aicompanion.util.EditorCommand;
import java.io.IOException;
import java.nio.file.*;

public class EditorLauncher {

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

    public String openAndRead() throws IOException, InterruptedException {
        String editor = EditorCommand.resolveEditor();
        Path tmp = Files.createTempFile("aicompanion-input-", ".md");
        try {
            String[] cmd = EditorCommand.argv(editor, tmp).toArray(String[]::new);
            int exit = spawner.run(cmd, tmp);
            if (exit != 0) return null;
            String content = Files.readString(tmp).trim();
            return content.isEmpty() ? null : content;
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private static int defaultSpawn(String[] cmd, Path file)
            throws IOException, InterruptedException {
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }
}
