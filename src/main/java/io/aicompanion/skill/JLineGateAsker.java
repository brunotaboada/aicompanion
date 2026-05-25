package io.aicompanion.skill;

import io.aicompanion.console.Ansi;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import java.nio.file.Path;

/**
 * Production gate prompt for {@link FeaturePipeline}: shows the question
 * "Continue to <next>? [Y]es / [e]dit <output> / [n]o" via JLine and maps the
 * keypress to a {@link FeaturePipeline.GateResponse}.
 *
 * <p>Empty input or Enter defaults to {@code CONTINUE} — the cheapest answer
 * is the most common one.
 */
public final class JLineGateAsker implements FeaturePipeline.GateAsker {

    private final LineReader reader;

    public JLineGateAsker(LineReader reader) {
        this.reader = reader;
    }

    @Override
    public FeaturePipeline.GateResponse ask(String justCompleted, String next, Path completedOutput) {
        System.out.println();
        System.out.println(Ansi.bold("Continue to " + next + "?"));
        System.out.println("  " + Ansi.green("[Y]") + "es"
            + "    " + Ansi.yellow("[e]") + "dit " + completedOutput.getFileName()
            + "    " + Ansi.red("[n]") + "o");
        while (true) {
            String line;
            try {
                line = reader.readLine("> ");
            } catch (UserInterruptException | EndOfFileException e) {
                return FeaturePipeline.GateResponse.STOP;
            }
            if (line == null) return FeaturePipeline.GateResponse.STOP;
            String r = line.trim().toLowerCase();
            if (r.isEmpty() || r.equals("y") || r.equals("yes")) {
                return FeaturePipeline.GateResponse.CONTINUE;
            }
            if (r.equals("e") || r.equals("edit")) {
                return FeaturePipeline.GateResponse.EDIT_AND_CONTINUE;
            }
            if (r.equals("n") || r.equals("no")) {
                return FeaturePipeline.GateResponse.STOP;
            }
            System.out.println(Ansi.dim("Please answer Y, e, or n."));
        }
    }
}
