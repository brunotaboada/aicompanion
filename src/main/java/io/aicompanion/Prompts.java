package io.aicompanion;

/**
 * Prompt templates sent to the agent. Centralised so the "summary format"
 * rules stay consistent across initial, fix-loop, init, and compact prompts
 * — and so token-saving tweaks land in one place.
 */
final class Prompts {

    private Prompts() {}

    /**
     * Compact one-liner appended to every prompt that needs to repeat the
     * rules in full. ~25 tokens instead of the multi-line block we used to
     * ship on every request.
     */
    private static final String SUMMARY_FORMAT_COMPACT =
        "End with 3-5 `- ` bullets (blank line above, no heading, no code, no commentary).";

    /**
     * Even shorter back-reference used when {@code init_instructions} is on
     * and the agent has already received the format rules once per session.
     */
    private static final String SUMMARY_FORMAT_REF =
        "End with a summary in the format established earlier.";

    /**
     * Sent once per session when {@code init_instructions=true}. The agent
     * keeps this in context so per-task prompts can reference it instead of
     * re-shipping the rules each time.
     */
    static String forSessionInit() {
        return "Session rules: every response you produce in this session must "
            + "end with a concise change summary. Format: blank line, then 3-5 "
            + "bullet points each on its own line starting with `- `. No heading "
            + "(no `## Summary`), no code, no commentary after the bullets. "
            + "Acknowledge with `ok`.";
    }

    /** First-attempt prompt with the format rules inline. */
    static String forTask(String taskBody) {
        return taskBody + "\n\n"
            + "When done, summarise your changes. " + SUMMARY_FORMAT_COMPACT;
    }

    /** First-attempt prompt that relies on a prior session-init message. */
    static String forTaskShort(String taskBody) {
        return taskBody + "\n\n" + SUMMARY_FORMAT_REF;
    }

    /**
     * Fix-loop prompt with truncated test output. {@code maxLines <= 0}
     * disables truncation (preserves old behaviour for callers that want the
     * full dump). {@code touchedFiles} — files the agent changed during this
     * task so far — anchors the diagnosis to its own edits instead of a fresh
     * repo exploration; empty/null adds nothing.
     */
    static String forFix(String testCommand, String testOutput, int maxLines,
                         java.util.List<String> touchedFiles) {
        return "Previous changes broke the tests. `" + testCommand + "` failed:\n\n"
            + "----- TEST OUTPUT -----\n"
            + truncateOutput(testOutput, maxLines)
            + "\n----- END OUTPUT -----\n\n"
            + touchedFilesSection(touchedFiles)
            + "Diagnose and fix without weakening the tests. "
            + SUMMARY_FORMAT_COMPACT;
    }

    /** Fix-loop prompt that relies on a prior session-init message. */
    static String forFixShort(String testCommand, String testOutput, int maxLines,
                              java.util.List<String> touchedFiles) {
        return "`" + testCommand + "` failed:\n\n"
            + "----- TEST OUTPUT -----\n"
            + truncateOutput(testOutput, maxLines)
            + "\n----- END OUTPUT -----\n\n"
            + touchedFilesSection(touchedFiles)
            + "Diagnose and fix without weakening the tests. "
            + SUMMARY_FORMAT_REF;
    }

    /** How many touched files a fix prompt lists before eliding the rest. */
    static final int TOUCHED_FILES_CAP = 20;

    /**
     * Bulleted list of the files changed so far in this task, so the fix
     * attempt starts from the likely culprits. Empty input → empty string.
     */
    static String touchedFilesSection(java.util.List<String> touchedFiles) {
        if (touchedFiles == null || touchedFiles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
            "Files you created or modified for this task (likely culprits):\n");
        int n = Math.min(touchedFiles.size(), TOUCHED_FILES_CAP);
        for (int i = 0; i < n; i++) {
            sb.append("- ").append(touchedFiles.get(i)).append('\n');
        }
        if (touchedFiles.size() > n) {
            sb.append("- … and ").append(touchedFiles.size() - n).append(" more\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Sent at the start of a fresh session when {@code compact_after_n_tasks}
     * triggers — a short handoff so the new session knows what is already done
     * without inheriting the full conversation.
     */
    static String forCompactHandoff(java.util.List<String> completedTaskNames) {
        if (completedTaskNames == null || completedTaskNames.isEmpty()) {
            return "Starting a fresh session. Continue from the next task.";
        }
        StringBuilder sb = new StringBuilder(
            "Context handoff from prior session. Already completed: ");
        for (int i = 0; i < completedTaskNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(completedTaskNames.get(i));
        }
        sb.append(". Acknowledge with `ok` and wait for the next task.");
        return sb.toString();
    }

    /**
     * Keep at most {@code maxLines} lines of test output: head + tail with an
     * elision marker. Returns the input unchanged when truncation is disabled
     * or the output is already short enough.
     */
    static String truncateOutput(String output, int maxLines) {
        if (output == null || output.isEmpty()) return "";
        if (maxLines <= 0) return output;
        String[] lines = output.split("\n", -1);
        if (lines.length <= maxLines) return output;
        int head = Math.max(1, maxLines / 3);
        int tail = maxLines - head;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < head; i++) sb.append(lines[i]).append('\n');
        sb.append("... [").append(lines.length - head - tail)
          .append(" line(s) elided] ...\n");
        for (int i = lines.length - tail; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Strip everything before the first Markdown heading ({@code #}, {@code ##}, etc.)
     * — feature-spec preambles often repeat 100+ lines of context that is
     * already implied by the per-task heading. Returns the input unchanged
     * when no heading is found (so plain-text task files are safe).
     */
    static String stripPreamble(String taskBody) {
        if (taskBody == null || taskBody.isEmpty()) return taskBody;
        String[] lines = taskBody.split("\n", -1);
        int firstHeading = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].stripLeading();
            if (trimmed.startsWith("#") && (trimmed.length() == 1
                    || trimmed.charAt(1) == '#' || trimmed.charAt(1) == ' ')) {
                firstHeading = i;
                break;
            }
        }
        if (firstHeading <= 0) return taskBody;
        StringBuilder sb = new StringBuilder();
        for (int i = firstHeading; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }
}
