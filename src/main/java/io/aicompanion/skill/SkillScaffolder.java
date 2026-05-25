package io.aicompanion.skill;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Copies the bundled skill defaults out of the JAR (or test classpath) into
 * {@code .agents/skills/} in the user's project. Powers {@code aicompanion
 * init skills} — the one-shot setup for fresh projects that don't yet have
 * their own skill bundles.
 *
 * <p>The Maven build (see {@code pom.xml}) maps {@code .agents/skills/} into
 * the JAR at {@code /skills/}, so this loader reads from a classpath URL that
 * works for both packaged jars and exploded test classpaths.
 */
public final class SkillScaffolder {

    private static final String CLASSPATH_ROOT = "/skills";

    /** Outcome of a scaffold call — what got created and what was skipped. */
    public record Result(List<String> created, List<String> skipped) {
        public boolean isEmpty() { return created.isEmpty() && skipped.isEmpty(); }
    }

    /**
     * Copy every bundled skill to {@code projectRoot/.agents/skills/}. Existing
     * skill directories are skipped unless {@code force} is true.
     */
    public Result scaffold(Path projectRoot, boolean force) throws IOException, URISyntaxException {
        URL src = SkillScaffolder.class.getResource(CLASSPATH_ROOT);
        if (src == null) {
            throw new IllegalStateException(
                "No bundled skills found at classpath:" + CLASSPATH_ROOT
                    + " — is this a stale JAR? Rebuild with `mvn package`.");
        }

        Path target = projectRoot.resolve(".agents/skills");
        Files.createDirectories(target);

        URI uri = src.toURI();
        if ("jar".equals(uri.getScheme())) {
            try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
                return copyTree(fs.getPath(CLASSPATH_ROOT), target, force);
            }
        } else {
            return copyTree(Path.of(uri), target, force);
        }
    }

    /**
     * Walk {@code src} and copy each skill directory to {@code target},
     * respecting the per-directory force/skip policy.
     */
    static Result copyTree(Path src, Path target, boolean force) throws IOException {
        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        try (Stream<Path> entries = Files.list(src)) {
            for (Path skillDir : entries.filter(Files::isDirectory).toList()) {
                String name = skillDir.getFileName().toString();
                Path dest = target.resolve(name);
                if (Files.exists(dest) && !force) {
                    skipped.add(name);
                    continue;
                }
                copyRecursive(skillDir, dest);
                created.add(name);
            }
        }

        Collections.sort(created);
        Collections.sort(skipped);
        return new Result(created, skipped);
    }

    private static void copyRecursive(Path src, Path dest) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path rel = src.relativize(p);
                Path out = dest.resolve(rel.toString());   // toString() so cross-FS Paths work
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
