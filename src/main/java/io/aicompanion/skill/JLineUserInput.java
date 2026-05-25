package io.aicompanion.skill;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

/**
 * {@link UserInput} backed by a JLine {@link LineReader}. Used by the shell
 * and by {@code Main} CLI mode so the chat's {@code you>} prompt has the same
 * line-editing keys (Ctrl+A, Ctrl+E, etc.) the rest of the tool already uses.
 *
 * <p>Ctrl+C ({@link UserInterruptException}) and Ctrl+D
 * ({@link EndOfFileException}) both return {@code null} so the chat loop
 * treats them as a clean abort.
 */
public final class JLineUserInput implements UserInput {

    private final LineReader reader;

    public JLineUserInput(LineReader reader) {
        this.reader = reader;
    }

    @Override
    public String readLine(String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (UserInterruptException | EndOfFileException e) {
            return null;
        }
    }
}
