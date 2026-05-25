package io.aicompanion.skill;

/**
 * The user's side of the chat. Production wires this to a JLine LineReader so
 * the shell's history, tab-completion, and keybindings keep working inside the
 * skill conversation. Tests inject a queue-backed implementation.
 */
@FunctionalInterface
public interface UserInput {

    /** Read one line. Return {@code null} on EOF (Ctrl+D) — caller treats it as abort. */
    String readLine(String prompt);
}
