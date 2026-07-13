package io.aicompanion.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Split {@code $EDITOR} / {@code $VISUAL} values that include arguments. */
public final class EditorCommand {

    private EditorCommand() {}

    public static String resolveEditor() {
        String e = System.getenv("VISUAL");
        if (e == null || e.isBlank()) e = System.getenv("EDITOR");
        if (e == null || e.isBlank()) e = "vi";
        return e;
    }

    /** argv for {@link ProcessBuilder}: editor tokens + the target file path. */
    public static List<String> argv(String editorEnv, Path file) {
        List<String> cmd = new ArrayList<>(split(editorEnv));
        cmd.add(file.toString());
        return cmd;
    }

    /** Whitespace split with simple single/double-quote support. */
    static List<String> split(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) tokens.add(cur.toString());
        if (tokens.isEmpty()) throw new IllegalArgumentException("Editor command is blank.");
        return tokens;
    }
}
