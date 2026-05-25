package io.aicompanion.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;

/**
 * Append-only conversation log for a skill chat. Each turn becomes a fenced
 * Markdown section so the file is human-readable AND machine-parseable later.
 * Used by {@code ChatLoop} as a free debugging artifact — when a PRD comes out
 * wrong, the transcript shows exactly which question got which answer.
 *
 * <p>I/O errors are reported once on stderr and then swallowed. A failed
 * transcript write is annoying but never important enough to abort an
 * otherwise-working session.
 */
public final class Transcript {

    private final Path file;
    private boolean ioErrorReported = false;

    public Transcript(Path file) {
        this.file = file;
    }

    public Path file() { return file; }

    public void recordHeader(String skillName, String featureName) {
        write("# Transcript: " + skillName + " · " + featureName + "\n\n"
            + "Started: " + Instant.now() + "\n\n"
            + "---\n\n");
    }

    public void recordOpeningPrompt(String prompt) {
        write("### system\n\n" + fence(prompt) + "\n\n");
    }

    public void recordUser(String text) {
        write("### user\n\n" + text + "\n\n");
    }

    public void recordAgent(String text) {
        if (text == null || text.isBlank()) return;
        write("### agent\n\n" + text + "\n\n");
    }

    public void recordOutcome(String outcome) {
        write("---\n\n**Outcome:** " + outcome + "  \n"
            + "**Ended:** " + Instant.now() + "\n");
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Append the chunk to the file, creating parents and the file itself on
     * first write. Reports the first I/O failure on stderr and stays quiet
     * thereafter so a broken disk doesn't spam the console.
     */
    private void write(String text) {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, text,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!ioErrorReported) {
                System.err.println("[transcript] write failed (" + file + "): " + e.getMessage()
                    + " — further write errors will be suppressed.");
                ioErrorReported = true;
            }
        } catch (UncheckedIOException e) {
            // createDirectories can throw UncheckedIOException on some FS impls
            if (!ioErrorReported) {
                System.err.println("[transcript] write failed (" + file + "): " + e.getMessage());
                ioErrorReported = true;
            }
        }
    }

    /** Wrap text in a fenced code block, escaping any inner triple-backtick. */
    private static String fence(String text) {
        String safe = text.replace("```", "``\\`");
        return "```\n" + safe + "\n```";
    }
}
