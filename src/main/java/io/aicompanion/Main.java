package io.aicompanion;

import io.aicompanion.config.Config;
import io.aicompanion.config.ConfigLoader;

public class Main {

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if ("--version".equals(arg) || "-v".equals(arg)) { System.out.println("aicompanion 1.0.0"); return; }
            if ("--help".equals(arg)    || "-h".equals(arg)) { System.out.println(Help.TEXT); return; }
        }

        FlagParser.ParseResult parsed = FlagParser.parse(args, 0);

        Config config;
        try {
            config = ConfigLoader.load(parsed.configOverrides());
        } catch (IllegalArgumentException e) {
            System.err.println("aicompanion: " + e.getMessage());
            System.exit(2);
            return;
        }

        if (parsed.nonInteractive()) {
            new TaskRunner(config, parsed.runOptions()).run();
        } else {
            new Shell(config).start();
        }
    }
}
