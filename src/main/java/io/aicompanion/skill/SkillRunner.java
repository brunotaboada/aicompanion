package io.aicompanion.skill;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ModelInfo;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionModelState;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModelRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import io.aicompanion.AgentConsole;
import io.aicompanion.agent.AcpClientFactory;
import io.aicompanion.agent.AgentRegistry;
import io.aicompanion.agent.AgentSpec;
import io.aicompanion.config.Config;
import io.aicompanion.console.Ansi;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * Runs one interactive skill session end-to-end: resolves the agent, opens an
 * ACP session, sets the per-skill model, prepares the {@link Transcript}, and
 * hands the rendered prompt to a {@link ChatLoop}.
 *
 * <p>Separate from {@code TaskRunner} on purpose — {@code TaskRunner} executes
 * pre-decomposed task files autonomously; {@code SkillRunner} is the
 * interactive PRD / TechSpec / task-decomposition counterpart that produces
 * those task files in the first place.
 */
public final class SkillRunner {

    /** Where the canonical skill bundles live, relative to the project root. */
    public static final Path SKILLS_ROOT = Path.of(".agents", "skills");

    private final Config       config;
    private final AgentConsole console;
    private final UserInput    userInput;
    private final SkillLoader  loader;

    public SkillRunner(Config config, AgentConsole console, UserInput userInput) {
        this(config, console, userInput, new SkillLoader(SKILLS_ROOT));
    }

    /** Visible for testing — lets a test inject a {@link SkillLoader} pointing at a temp dir. */
    public SkillRunner(Config config, AgentConsole console, UserInput userInput, SkillLoader loader) {
        this.config    = config;
        this.console   = console;
        this.userInput = userInput;
        this.loader    = loader;
    }

    /**
     * Drive the skill to completion. Returns the {@link ChatLoop.Outcome} for
     * the caller (Shell or FeaturePipeline) to react to.
     *
     * @param skillName      directory under {@code .agents/skills/}
     * @param featureName    feature slug; resolves to {@code <featuresDir>/<featureName>/}
     * @param seedFile       optional explicit seed file (overrides {@code _idea.md} auto-detect)
     * @param modelOverride  optional --model flag; overrides {@code skills.<name>.model}
     */
    public ChatLoop.Outcome run(String skillName,
                                 String featureName,
                                 Path seedFile,
                                 String modelOverride) throws IOException {
        if (featureName == null || featureName.isBlank()) {
            throw new IllegalArgumentException("Feature name is required.");
        }

        Path featureDir = Path.of(config.featuresDir()).resolve(featureName);
        Files.createDirectories(featureDir);
        Files.createDirectories(featureDir.resolve("adrs"));

        SkillMetadata md = loader.describe(skillName);
        Path outputFile = featureDir.resolve(md.outputRelativePath());
        boolean updateMode = Files.exists(outputFile);

        SkillContext ctx = new SkillContext(featureName, featureDir,
            (seedFile != null && Files.exists(seedFile)) ? seedFile : null,
            updateMode);
        Skill skill = loader.load(skillName, ctx);

        AgentSpec spec  = AgentRegistry.resolve(config);
        String   model  = modelOverride != null && !modelOverride.isBlank()
            ? modelOverride
            : config.modelFor(skillName);

        printBanner(skill, featureName, outputFile, spec, model, updateMode);

        Path transcriptFile = transcriptPathFor(featureDir, md);
        Transcript transcript = new Transcript(transcriptFile);
        transcript.recordHeader(skillName, featureName);

        var transport = new StdioAcpClientTransport(spec.params(config).get());
        try (AcpSyncClient client = AcpClientFactory.build(transport, config, console)) {

            client.initialize(new InitializeRequest(1,
                new ClientCapabilities(new FileSystemCapability(true, true), false)));

            NewSessionResponse session = client.newSession(
                new NewSessionRequest(Path.of(config.projectDir()).toAbsolutePath().toString(),
                    List.of()));
            String sessionId = session.sessionId();
            System.out.println(Ansi.dim("Session: " + sessionId));
            selectModel(client, sessionId, session.models(), model);
            System.out.println();

            ChatLoop.PromptSender sender = prompt ->
                client.prompt(new PromptRequest(sessionId,
                    List.of(new TextContent(prompt))));

            ChatLoop loop = new ChatLoop(
                sender, userInput, new EditorLauncher(), console,
                outputFile, transcript, null);

            ChatLoop.Outcome outcome = loop.run(skill.body());
            printFooter(outcome, outputFile, transcriptFile);
            return outcome;
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** {@code _prd.md} → {@code _prd.transcript.md}. Output without `.md` gets `.transcript.md` appended. */
    static Path transcriptPathFor(Path featureDir, SkillMetadata md) {
        String out = md.outputRelativePath();
        String name = out.endsWith(".md")
            ? out.substring(0, out.length() - ".md".length()) + ".transcript.md"
            : out + ".transcript.md";
        return featureDir.resolve(name);
    }

    private void printBanner(Skill skill, String featureName, Path outputFile,
                              AgentSpec spec, String model, boolean updateMode) {
        System.out.println();
        System.out.println(Ansi.bold(Ansi.cyan("═══ " + skill.metadata().name()
            + "  ·  " + featureName + " ═══")));
        System.out.println(Ansi.dim(skill.metadata().description()));
        System.out.println();
        row("Skill",   skill.metadata().name());
        row("Feature", featureName);
        row("Output",  outputFile.toString() + (updateMode ? Ansi.yellow("  (update mode — preserving existing sections)") : ""));
        row("Agent",   spec.id());
        row("Model",   model != null ? model : Ansi.dim("(agent default)"));
        System.out.println();
        System.out.println(Ansi.dim(
            "Sentinels: /abort  /done  /skip  /edit   "
            + "(Ctrl+D from `you>` also aborts)"));
        System.out.println();
    }

    private static void row(String key, String val) {
        System.out.printf("  %-8s %s%n", key, val);
    }

    private static void printFooter(ChatLoop.Outcome outcome, Path outputFile, Path transcriptFile) {
        System.out.println();
        switch (outcome) {
            case COMPLETED -> {
                System.out.println(Ansi.green("✓ Created " + outputFile));
                System.out.println(Ansi.dim("  transcript: " + transcriptFile));
            }
            case DONE_FORCED -> {
                if (Files.exists(outputFile)) {
                    System.out.println(Ansi.green("✓ Created " + outputFile + " (forced via /done)"));
                } else {
                    System.out.println(Ansi.yellow("⚠ /done — no output written. See transcript: " + transcriptFile));
                }
            }
            case ABORTED -> {
                System.out.println(Ansi.yellow("✗ Aborted. No output written. See transcript: " + transcriptFile));
            }
        }
    }

    /**
     * If a model was resolved, switch the session to it. Same matching logic as
     * {@code TaskRunner.selectModel} — permissive: "sonnet" → "claude-sonnet-4-6".
     */
    private static void selectModel(AcpSyncClient client, String sessionId,
                                     SessionModelState modelState, String want) {
        if (want == null || want.isBlank()) return;
        if (modelState == null || modelState.availableModels() == null
                || modelState.availableModels().isEmpty()) {
            System.out.println(Ansi.dim("[model] agent advertised no models — ignoring '" + want + "'"));
            return;
        }
        ModelInfo match = findModel(modelState.availableModels(), want);
        if (match == null) {
            System.out.println(Ansi.dim("[model] no match for '" + want + "' — staying on default"));
            return;
        }
        if (match.modelId().equals(modelState.currentModelId())) {
            System.out.println(Ansi.dim("[model] using " + match.modelId() + " (already active)"));
            return;
        }
        client.setSessionModel(new SetSessionModelRequest(sessionId, match.modelId()));
        System.out.println(Ansi.dim("[model] switched to " + match.modelId()));
    }

    static ModelInfo findModel(List<ModelInfo> models, String want) {
        String w = want.toLowerCase().trim();
        for (ModelInfo m : models) if (m.modelId().equalsIgnoreCase(want)) return m;
        for (ModelInfo m : models) if (m.name() != null && m.name().equalsIgnoreCase(want)) return m;
        ModelInfo best = null;
        for (ModelInfo m : models) {
            String id   = m.modelId().toLowerCase();
            String name = m.name() == null ? "" : m.name().toLowerCase();
            if (id.contains(w) || name.contains(w)) {
                if (best == null || m.modelId().length() > best.modelId().length()) best = m;
            }
        }
        return best;
    }
}
