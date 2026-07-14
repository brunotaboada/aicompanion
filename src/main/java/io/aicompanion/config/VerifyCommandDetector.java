package io.aicompanion.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds an ordered, fail-fast verification pipeline from project markers when
 * the user has not set {@code verify_commands}. Cheap checks (lint, typecheck)
 * come before the test suite so failures short-circuit without paying for a
 * full test run.
 *
 * <p>Returns an empty list when no cheap checks are found — callers then fall
 * back to the single {@code test_command}. Explicit {@code verify_commands} in
 * config always win and are never reordered.
 */
final class VerifyCommandDetector {

    private static final Pattern MAKE_TARGET =
        Pattern.compile("^([A-Za-z_][A-Za-z0-9_-]*)\\s*:", Pattern.MULTILINE);

    private VerifyCommandDetector() {}

    /**
     * Detect a multi-step pipeline, or {@link List#of()} when only a single
     * test command would apply (leave that to {@code test_command} auto-detect).
     */
    static List<String> detect(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<String> npm = fromPackageJson(root.resolve("package.json"));
        if (!npm.isEmpty()) return npm;
        List<String> make = fromMakefile(root.resolve("Makefile"));
        if (!make.isEmpty()) return make;
        return List.of();
    }

    static List<String> fromPackageJson(Path packageJson) {
        if (!Files.isRegularFile(packageJson)) return List.of();
        Set<String> scripts;
        try {
            scripts = readJsonObjectKeys(Files.readString(packageJson), "scripts");
        } catch (IOException e) {
            return List.of();
        }
        List<String> cheap = new ArrayList<>();
        if (scripts.contains("lint")) cheap.add("npm run lint");
        if (scripts.contains("typecheck")) cheap.add("npm run typecheck");
        else if (scripts.contains("type-check")) cheap.add("npm run type-check");
        if (cheap.isEmpty()) return List.of();

        List<String> pipeline = new ArrayList<>(cheap);
        if (scripts.contains("test")) pipeline.add("npm test");
        return List.copyOf(pipeline);
    }

    static List<String> fromMakefile(Path makefile) {
        if (!Files.isRegularFile(makefile)) return List.of();
        Set<String> targets;
        try {
            targets = readMakeTargets(Files.readString(makefile));
        } catch (IOException e) {
            return List.of();
        }
        List<String> cheap = new ArrayList<>();
        if (targets.contains("lint")) cheap.add("make lint");
        if (targets.contains("typecheck")) cheap.add("make typecheck");
        else if (targets.contains("type-check")) cheap.add("make type-check");
        if (cheap.isEmpty()) return List.of();

        List<String> pipeline = new ArrayList<>(cheap);
        if (targets.contains("test")) pipeline.add("make test");
        return List.copyOf(pipeline);
    }

    /**
     * Collect top-level string keys of the JSON object named {@code field}
     * (e.g. {@code "scripts"}). Tolerates nested braces inside string values.
     * Returns an empty set on malformed input rather than throwing.
     */
    static Set<String> readJsonObjectKeys(String json, String field) {
        Set<String> keys = new LinkedHashSet<>();
        if (json == null || field == null || field.isBlank()) return keys;

        Pattern fieldPat = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*\\{", Pattern.CASE_INSENSITIVE);
        Matcher m = fieldPat.matcher(json);
        if (!m.find()) return keys;

        int i = m.end(); // position just after the opening '{'
        boolean inString = false;
        boolean escape = false;
        int depth = 1;
        StringBuilder keyBuf = new StringBuilder();
        boolean readingKey = false;
        boolean expectKey = true;

        while (i < json.length() && depth > 0) {
            char c = json.charAt(i++);
            if (inString) {
                if (escape) {
                    if (readingKey) keyBuf.append(c);
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                    if (readingKey && depth == 1 && expectKey) {
                        keys.add(keyBuf.toString());
                        keyBuf.setLength(0);
                        readingKey = false;
                        expectKey = false;
                    }
                    continue;
                }
                if (readingKey) keyBuf.append(c);
                continue;
            }
            if (c == '"') {
                inString = true;
                if (depth == 1 && expectKey) {
                    readingKey = true;
                    keyBuf.setLength(0);
                }
                continue;
            }
            if (c == '{') {
                depth++;
                expectKey = false;
            } else if (c == '}') {
                depth--;
            } else if (c == ',' && depth == 1) {
                expectKey = true;
            } else if (c == ':' && depth == 1) {
                expectKey = false;
            }
        }
        return keys;
    }

    static Set<String> readMakeTargets(String makefile) {
        Set<String> targets = new LinkedHashSet<>();
        if (makefile == null || makefile.isBlank()) return targets;
        Matcher m = MAKE_TARGET.matcher(makefile);
        while (m.find()) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            // Skip Make's special/implicit-looking targets
            if (name.startsWith(".") || name.equals("PHONY")) continue;
            targets.add(name);
        }
        return targets;
    }
}
