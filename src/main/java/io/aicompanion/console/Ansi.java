package io.aicompanion.console;

/**
 * ANSI escape helpers. Colors auto-disable when stdout is not a TTY,
 * when NO_COLOR is set (https://no-color.org/), or when TERM=dumb.
 */
public final class Ansi {

    private static final boolean ENABLED = detect();

    private static final String RESET  = "[0m";
    private static final String BOLD   = "[1m";
    private static final String DIM    = "[2m";
    private static final String RED    = "[31m";
    private static final String GREEN  = "[32m";
    private static final String YELLOW = "[33m";
    private static final String CYAN   = "[36m";

    private Ansi() {}

    public static boolean enabled() { return ENABLED; }

    public static String green(String s)  { return wrap(GREEN, s); }
    public static String red(String s)    { return wrap(RED, s); }
    public static String yellow(String s) { return wrap(YELLOW, s); }
    public static String cyan(String s)   { return wrap(CYAN, s); }
    public static String bold(String s)   { return wrap(BOLD, s); }
    public static String dim(String s)    { return wrap(DIM, s); }

    public static String rule() { return dim("─".repeat(60)); }

    private static String wrap(String code, String s) {
        if (!ENABLED || s == null || s.isEmpty()) return s;
        return code + s + RESET;
    }

    private static boolean detect() {
        if (System.getenv("NO_COLOR") != null) return false;
        String term = System.getenv("TERM");
        if (term != null && term.equalsIgnoreCase("dumb")) return false;
        return System.console() != null;
    }
}
