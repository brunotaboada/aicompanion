package io.aicompanion;

/**
 * Prompt templates sent to the agent. Centralised here so the "summary
 * format" rules stay identical across the initial and fix-loop prompts and
 * can be tuned in one place.
 */
final class Prompts {

    private Prompts() {}

    /**
     * Bullet rules appended to every prompt so the agent's trailing summary
     * lands in a predictable shape for the per-task log.
     */
    private static final String SUMMARY_FORMAT = """
        Format requirements:
          • Precede the summary with a blank line.
          • 3–5 bullet points, each on its own line, starting with `- `.
          • Do NOT include a heading like `## Summary` or `# Summary`.
          • Do NOT repeat any code.
          • Do NOT add commentary after the bullets.""";

    /** Prompt for the first attempt at a task: the task body + summary rules. */
    static String forTask(String taskBody) {
        return taskBody + "\n\n"
            + "When you are done, end your response with a concise summary "
            + "of what you changed or created. " + SUMMARY_FORMAT;
    }

    /**
     * Prompt for the fix loop after tests fail: includes the failing test
     * command, the captured output, and the same summary rules.
     */
    static String forFix(String testCommand, String testOutput) {
        return "Your previous changes broke the test suite. "
            + "The test command `" + testCommand + "` failed "
            + "with the output below.\n\n"
            + "----- TEST OUTPUT -----\n"
            + testOutput
            + "\n----- END OUTPUT -----\n\n"
            + "Diagnose the failure, fix it (edit existing code, do not "
            + "delete or weaken the failing tests), then end your response "
            + "with a concise summary. " + SUMMARY_FORMAT;
    }
}
