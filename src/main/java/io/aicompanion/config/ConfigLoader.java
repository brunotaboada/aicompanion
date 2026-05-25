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
        return load(cliOverrides, file, System.getenv());
    }

    /** Visible for testing – allows injecting env vars without mutating process state. */
    public static Config load(Map<String, String> cliOverrides,
                              Map<String, Object> file,
                              Map<String, String> env) {
        return new Config(
            resolve("agent",               cliOverrides, file, env, null),
            resolve("model",               cliOverrides, file, env, null),
            resolveList("agent_extra_args", cliOverrides, file, env, List.of()),
            resolve("features_dir",        cliOverrides, file, env, "features"),
            resolveList("task_extensions",  cliOverrides, file, env, List.of("md", "txt")),
            resolve("task_sort",           cliOverrides, file, env, "alphabetical"),
            resolve("project_dir",         cliOverrides, file, env, "."),
            resolve("test_command",        cliOverrides, file, env, autoDetectTestCommand()),
            resolveBoolean("test_enabled",     cliOverrides, file, env, true),
            resolveBoolean("stop_on_failure",  cliOverrides, file, env, true),
            resolveInt("max_fix_attempts",     cliOverrides, file, env, 3),
            resolveInt("session_timeout_min",  cliOverrides, file, env, 10),
            resolveBoolean("reuse_session",    cliOverrides, file, env, true),
            resolve("report_dir",          cliOverrides, file, env, ".aicompanion/logs"),
            resolveBoolean("report_enabled",   cliOverrides, file, env, true),
            resolveBoolean("log_tool_calls",   cliOverrides, file, env, true),
            resolveBoolean("log_thoughts",     cliOverrides, file, env, false),
            resolveBoolean("yolo",             cliOverrides, file, env, true),
            resolveInt("fix_output_max_lines",     cliOverrides, file, env, 200),
            resolveBoolean("task_preamble_strip",   cliOverrides, file, env, false),
            resolveInt("compact_after_n_tasks",     cliOverrides, file, env, 0),
            resolveBoolean("pre_check_tests",       cliOverrides, file, env, false),
            resolveInt("max_tokens_per_run",        cliOverrides, file, env, 0),
            resolveBoolean("init_instructions",     cliOverrides, file, env, false),
            resolveSkills(file, env)
        );
    }

    /** Priority: CLI flag → AICOMPANION_<KEY> env var → config file → default */
    private static String resolve(String key, Map<String, String> cli,
                                  Map<String, Object> file,
                                  Map<String, String> env, String def) {
        if (cli != null && cli.containsKey(key)) return cli.get(key);
        String envVal = env.get("AICOMPANION_" + key.toUpperCase());
        if (envVal != null && !envVal.isBlank()) return envVal;
        Object val = file.get(key);
        if (val != null) return String.valueOf(val);
        return def;
    }

    private static boolean resolveBoolean(String key, Map<String, String> cli,
                                           Map<String, Object> file,
                                           Map<String, String> env, boolean def) {
        String val = resolve(key, cli, file, env, null);
        if (val == null) return def;
        return "true".equalsIgnoreCase(val) || "1".equals(val) || "yes".equalsIgnoreCase(val);
    }

    private static int resolveInt(String key, Map<String, String> cli,
                                   Map<String, Object> file,
                                   Map<String, String> env, int def) {
        String val = resolve(key, cli, file, env, null);
        if (val == null) return def;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return def; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> resolveList(String key, Map<String, String> cli,
                                             Map<String, Object> file,
                                             Map<String, String> env, List<String> def) {
        if (cli != null && cli.containsKey(key)) return Arrays.asList(cli.get(key).split(","));
        String envVal = env.get("AICOMPANION_" + key.toUpperCase());
        if (envVal != null && !envVal.isBlank()) return Arrays.asList(envVal.split(","));
        Object val = file.get(key);
        if (val instanceof List<?> list) return (List<String>) list;
        if (val instanceof String s) return Arrays.asList(s.split(","));
        return def;
    }

    /**
     * Parse the {@code skills:} mapping plus any {@code AICOMPANION_SKILLS_<NAME>_MODEL}
     * env vars. Env vars win over the file (matching the priority used for every other key).
     * Skill names in env vars use {@code _} as the word separator and are folded to
     * lowercase with {@code _} → {@code -}, so {@code CREATE_PRD} becomes {@code create-prd}.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Config.SkillConfig> resolveSkills(Map<String, Object> file,
                                                          Map<String, String> env) {
        Map<String, Config.SkillConfig> result = new LinkedHashMap<>();

        Object raw = file.get("skills");
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String name = String.valueOf(e.getKey());
                String model = null;
                if (e.getValue() instanceof Map<?, ?> sub) {
                    Object m = sub.get("model");
                    if (m != null) model = String.valueOf(m);
                }
                result.put(name, new Config.SkillConfig(model));
            }
        }

        String prefix = "AICOMPANION_SKILLS_";
        String suffix = "_MODEL";
        for (Map.Entry<String, String> e : env.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(prefix) || !key.endsWith(suffix)) continue;
            String middle = key.substring(prefix.length(), key.length() - suffix.length());
            if (middle.isEmpty()) continue;
            String skillName = middle.toLowerCase().replace('_', '-');
            String value = e.getValue();
            if (value == null || value.isBlank()) continue;
            result.put(skillName, new Config.SkillConfig(value));
        }

        return Map.copyOf(result);
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
