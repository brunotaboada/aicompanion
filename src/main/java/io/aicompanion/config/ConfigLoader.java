package io.aicompanion.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class ConfigLoader {

    public static Config load(Map<String, String> cliOverrides) {
        return load(cliOverrides, loadFile());
    }

    /** Visible for testing – bypasses file loading. */
    public static Config load(Map<String, String> cliOverrides, Map<String, Object> file) {
        return new Config(
            resolve("agent",               cliOverrides, file, null),
            resolve("model",               cliOverrides, file, null),
            resolveList("agent_extra_args", cliOverrides, file, List.of()),
            resolve("features_dir",        cliOverrides, file, "features"),
            resolveList("task_extensions",  cliOverrides, file, List.of("md", "txt")),
            resolve("task_sort",           cliOverrides, file, "alphabetical"),
            resolve("project_dir",         cliOverrides, file, "."),
            resolve("test_command",        cliOverrides, file, autoDetectTestCommand()),
            resolveBoolean("test_enabled",     cliOverrides, file, true),
            resolveBoolean("stop_on_failure",  cliOverrides, file, true),
            resolveInt("max_fix_attempts",     cliOverrides, file, 3),
            resolveInt("session_timeout_min",  cliOverrides, file, 10),
            resolveBoolean("reuse_session",    cliOverrides, file, true),
            resolve("report_dir",          cliOverrides, file, ".aicompanion/logs"),
            resolveBoolean("report_enabled",   cliOverrides, file, true),
            resolveBoolean("log_tool_calls",   cliOverrides, file, true),
            resolveBoolean("log_thoughts",     cliOverrides, file, false),
            resolveBoolean("yolo",             cliOverrides, file, true)
        );
    }

    /** Priority: CLI flag → AICOMPANION_<KEY> env var → config file → default */
    private static String resolve(String key, Map<String, String> cli,
                                  Map<String, Object> file, String def) {
        if (cli != null && cli.containsKey(key)) return cli.get(key);
        String env = System.getenv("AICOMPANION_" + key.toUpperCase());
        if (env != null && !env.isBlank()) return env;
        Object val = file.get(key);
        if (val != null) return String.valueOf(val);
        return def;
    }

    private static boolean resolveBoolean(String key, Map<String, String> cli,
                                           Map<String, Object> file, boolean def) {
        String val = resolve(key, cli, file, null);
        if (val == null) return def;
        return "true".equalsIgnoreCase(val) || "1".equals(val) || "yes".equalsIgnoreCase(val);
    }

    private static int resolveInt(String key, Map<String, String> cli,
                                   Map<String, Object> file, int def) {
        String val = resolve(key, cli, file, null);
        if (val == null) return def;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return def; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> resolveList(String key, Map<String, String> cli,
                                             Map<String, Object> file, List<String> def) {
        if (cli != null && cli.containsKey(key)) return Arrays.asList(cli.get(key).split(","));
        String env = System.getenv("AICOMPANION_" + key.toUpperCase());
        if (env != null && !env.isBlank()) return Arrays.asList(env.split(","));
        Object val = file.get(key);
        if (val instanceof List<?> list) return (List<String>) list;
        if (val instanceof String s) return Arrays.asList(s.split(","));
        return def;
    }

    private static Map<String, Object> loadFile() {
        return loadFileFrom(Path.of(".aicompanion.yml"));
    }

    /**
     * Load and validate {@code .aicompanion.yml}. Visible for testing.
     *
     * <p>Failure modes are surfaced as {@link IllegalArgumentException} with
     * a message that names the file and the underlying cause — silently
     * falling back to defaults on a typo'd config bit users in the past.
     * A missing file is fine; an unreadable, malformed, or non-mapping file
     * is not.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadFileFrom(Path path) {
        if (!Files.exists(path)) return new HashMap<>();
        try (var reader = Files.newBufferedReader(path)) {
            Object raw = new Yaml().load(reader);
            if (raw == null) return new HashMap<>();   // empty file
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                    "Invalid " + path + ": expected a YAML mapping at the top level, got "
                        + raw.getClass().getSimpleName());
            }
            return (Map<String, Object>) map;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                "Could not read " + path + ": " + e.getMessage(), e);
        } catch (YAMLException e) {
            throw new IllegalArgumentException(
                "Could not parse " + path + ": " + e.getMessage(), e);
        }
    }

    private static String autoDetectTestCommand() {
        if (Files.exists(Path.of("pom.xml")))      return "mvn test -q";
        if (Files.exists(Path.of("build.gradle"))) return "gradle test";
        if (Files.exists(Path.of("package.json"))) return "npm test";
        if (Files.exists(Path.of("Makefile")))     return "make test";
        return null;
    }
}
