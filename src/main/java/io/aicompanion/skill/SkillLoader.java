package io.aicompanion.skill;

import io.aicompanion.util.PathSecurity;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers, describes, and renders skills from {@code .agents/skills/} in the
 * project root.
 *
 * <p>A skill is a directory with a {@code SKILL.md} file. The file starts with
 * YAML frontmatter declaring {@code description} and {@code output} (the path
 * relative to {@code features/<feature>/} that the agent will write to signal
 * completion). The body is plain Markdown with {@code {{placeholder}}} tokens
 * that {@link #load} substitutes per-invocation.
 *
 * <p>Errors throw {@link SkillLoadException} with the offending file in the
 * message — silently falling back to defaults on a typo'd skill would let the
 * agent run with the wrong instructions, which is much worse than failing fast.
 */
public class SkillLoader {

    /** Recognised placeholder tokens. See {@link #render} for the substitution map. */
    static final List<String> PLACEHOLDERS = List.of(
        "{{feature}}", "{{feature_dir}}", "{{output_file}}",
        "{{adr_dir}}", "{{seed_file}}", "{{update_mode}}",
        "{{templates_dir}}", "{{shared_templates_dir}}"
    );

    private final Path skillsRoot;

    public SkillLoader(Path skillsRoot) {
        this.skillsRoot = skillsRoot;
    }

    /**
     * Skill directory names found at {@link #skillsRoot}, sorted alphabetically.
     * Returns an empty list when the root does not exist — fresh projects with
     * no skills yet are not an error.
     */
    public List<String> discover() throws IOException {
        if (!Files.isDirectory(skillsRoot)) return List.of();
        try (Stream<Path> entries = Files.list(skillsRoot)) {
            return entries
                .filter(Files::isDirectory)
                .filter(p -> Files.isRegularFile(p.resolve("SKILL.md")))
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        }
    }

    /**
     * Parse only the frontmatter of a skill. Cheap — used by the {@code skills}
     * command to list skills without reading every body.
     */
    public SkillMetadata describe(String skillName) throws IOException {
        Path skillFile = skillFileFor(skillName);
        String raw = Files.readString(skillFile);
        Frontmatter fm = parseFrontmatter(raw, skillFile);
        return toMetadata(skillName, fm, skillFile);
    }

    /**
     * Load a skill and render it for a specific feature invocation. The returned
     * body has all placeholders substituted and the frontmatter stripped — it can
     * be shipped directly as the first prompt of a chat session.
     */
    public Skill load(String skillName, SkillContext ctx) throws IOException {
        Path skillFile = skillFileFor(skillName);
        String raw = Files.readString(skillFile);
        Frontmatter fm = parseFrontmatter(raw, skillFile);
        SkillMetadata metadata = toMetadata(skillName, fm, skillFile);

        Path outputFile = PathSecurity.resolveContained(
            ctx.featureDir(), metadata.outputRelativePath(), "output");
        Path skillDir = skillFile.getParent();

        String rendered = render(fm.body(), ctx, metadata, skillDir, outputFile, skillsRoot);
        return new Skill(metadata, rendered, outputFile);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Path skillFileFor(String skillName) {
        String safeName = PathSecurity.validateSegment(skillName, "Skill name");
        Path rootAbs  = skillsRoot.toAbsolutePath().normalize();
        Path skillDir = rootAbs.resolve(safeName).normalize();
        if (!skillDir.startsWith(rootAbs)) {
            throw new SkillLoadException("Skill path escapes skills root: " + skillName);
        }
        Path skillFile = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillFile)) {
            throw new SkillLoadException(
                "Skill '" + skillName + "' not found at " + skillFile
                    + ". Run `aicompanion init skills` to scaffold the canonical bundles.");
        }
        return skillFile;
    }

    /**
     * Split YAML frontmatter from the body. Frontmatter starts at line 1 with
     * {@code ---} and ends at the next standalone {@code ---} line. Missing
     * frontmatter is an error — every SKILL.md must declare its output path.
     */
    static Frontmatter parseFrontmatter(String raw, Path skillFile) {
        String[] lines = raw.split("\n", -1);
        if (lines.length < 3 || !"---".equals(lines[0].trim())) {
            throw new SkillLoadException(
                "Skill at " + skillFile + " is missing required YAML frontmatter "
                    + "(must start with `---` on line 1).");
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) { end = i; break; }
        }
        if (end == -1) {
            throw new SkillLoadException(
                "Skill at " + skillFile + " has an unterminated frontmatter block "
                    + "(no closing `---`).");
        }
        String yamlText = String.join("\n", Arrays.copyOfRange(lines, 1, end));
        Map<String, Object> yaml;
        try {
            Object parsed = new Yaml().load(yamlText);
            if (parsed == null) parsed = Map.of();
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new SkillLoadException(
                    "Skill at " + skillFile + " has frontmatter that is not a YAML mapping.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            yaml = cast;
        } catch (YAMLException e) {
            throw new SkillLoadException(
                "Skill at " + skillFile + " has invalid YAML frontmatter: " + e.getMessage(), e);
        }

        // Body starts after the closing `---`, skipping a single blank line if present.
        int bodyStart = end + 1;
        if (bodyStart < lines.length && lines[bodyStart].isBlank()) bodyStart++;
        String body = String.join("\n", Arrays.copyOfRange(lines, bodyStart, lines.length));
        return new Frontmatter(yaml, body);
    }

    private static SkillMetadata toMetadata(String skillName, Frontmatter fm, Path skillFile) {
        Object desc = fm.yaml().get("description");
        Object out  = fm.yaml().get("output");
        Object completion = fm.yaml().get("completion");
        if (desc == null || String.valueOf(desc).isBlank()) {
            throw new SkillLoadException(
                "Skill at " + skillFile + " is missing required frontmatter field `description`.");
        }
        if (out == null || String.valueOf(out).isBlank()) {
            throw new SkillLoadException(
                "Skill at " + skillFile + " is missing required frontmatter field `output`.");
        }
        String outputPath = String.valueOf(out);
        String completionPath = completion != null && !String.valueOf(completion).isBlank()
            ? String.valueOf(completion) : null;
        PathSecurity.validateRelativePath(outputPath, "output");
        if (completionPath != null) {
            PathSecurity.validateRelativePath(completionPath, "completion");
        }
        return new SkillMetadata(skillName, String.valueOf(desc), outputPath, completionPath);
    }

    /**
     * Substitute every {@link #PLACEHOLDERS} token in {@code body} with values
     * derived from {@code ctx}. Paths are serialised with forward slashes so the
     * agent sees consistent input on any platform.
     */
    static String render(String body, SkillContext ctx, SkillMetadata metadata,
                         Path skillDir, Path outputFile, Path skillsRoot) {
        Path seed = ctx.seedFile() != null
            ? ctx.seedFile()
            : ctx.featureDir().resolve("_idea.md");

        Map<String, String> subs = new LinkedHashMap<>();
        subs.put("{{feature}}",        ctx.featureName());
        subs.put("{{feature_dir}}",    asPosix(ctx.featureDir()) + "/");
        subs.put("{{output_file}}",    asPosix(outputFile));
        subs.put("{{adr_dir}}",        asPosix(ctx.featureDir().resolve("adrs")) + "/");
        subs.put("{{seed_file}}",      asPosix(seed));
        subs.put("{{update_mode}}",    ctx.updateMode() ? "yes" : "no");
        subs.put("{{templates_dir}}",        asPosix(skillDir.resolve("references")) + "/");
        subs.put("{{shared_templates_dir}}", asPosix(skillsRoot.resolve("_shared/references")) + "/");

        String rendered = body;
        for (Map.Entry<String, String> e : subs.entrySet()) {
            rendered = rendered.replace(e.getKey(), e.getValue());
        }
        return rendered;
    }

    /** Normalise a path to forward slashes so prompts read the same on every OS. */
    static String asPosix(Path p) {
        return p.toString().replace('\\', '/');
    }

    /** Result of {@link #parseFrontmatter}: the YAML mapping plus the body text. */
    record Frontmatter(Map<String, Object> yaml, String body) {}
}
