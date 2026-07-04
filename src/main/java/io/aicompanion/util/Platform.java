package io.aicompanion.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** OS detection and PATH/PATHEXT-aware executable lookup. */
public final class Platform {

    private Platform() {}

    public static boolean isWindows() {
        return isWindows(System.getProperty("os.name", ""));
    }

    public static boolean isMac() {
        return isMac(System.getProperty("os.name", ""));
    }

    static boolean isWindows(String osName) {
        return osName.toLowerCase().startsWith("windows");
    }

    static boolean isMac(String osName) {
        return osName.toLowerCase().contains("mac");
    }

    /** Resolve `name` against PATH, applying PATHEXT on Windows. Returns null if not found. */
    public static File findOnPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        boolean win = isWindows();
        String[] exts = win ? windowsExtensions() : new String[]{""};
        for (String dir : pathEnv.split(File.pathSeparator)) {
            for (String ext : exts) {
                File f = new File(dir, name + ext);
                if (f.isFile() && (win || f.canExecute())) return f;
            }
        }
        return null;
    }

    private static String[] windowsExtensions() {
        String pathExt = System.getenv("PATHEXT");
        if (pathExt == null || pathExt.isBlank()) pathExt = ".COM;.EXE;.BAT;.CMD";
        List<String> exts = new ArrayList<>();
        exts.add("");
        for (String e : pathExt.split(";")) if (!e.isBlank()) exts.add(e.trim());
        return exts.toArray(new String[0]);
    }
}
