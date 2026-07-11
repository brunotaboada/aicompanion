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
import io.aicompanion.util.PathSecurity;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public final class SkillRunner {

    /** Relative path from {@link #skillsRoot(Config)} — kept for messages and tests. */
    public static final Path SKILLS_ROOT = Path.of(".agents", "skills");

    private final Config       config;
    private final AgentConsole console;
    private final UserInput    userInput;
    private final SkillLoader  loader;

    public static Path skillsRoot(Config config) {
        return Path.of(config.projectDir()).toAbsolutePath().normalize().resolve(SKILLS_ROOT);
    }

    public SkillRunner(Config config, AgentConsole console, UserInput userInput) {
        this(config, console, userInput, new SkillLoader(skillsRoot(config)));
    }

    public SkillRunner(Config config, AgentConsole console, UserInput userInput, SkillLoader loader) {
        this.config    = config;
        this.console   = console;
        this.userInput = userInput;
        this.loader    = loader;
    }

    public ChatLoop.Outcome run(String skillName,
                                 String featureName,
                                 Path seedFile,
                                 String modelOverride) throws IOException {
        if (featureName == null || featureName.isBlank()) {
            throw new IllegalArgumentException("Feature name is required.");
        }
        String safeFeature = PathSecurity.validateSegment(featureName, "Feature name");

        if (seedFile != null && !Files.exists(seedFile)) {
            throw new SkillLoadException("Seed file not found: " + seedFile);
        }

        Path featureDir = Path.of(config.featuresDir()).resolve(safeFeature);
        Files.createDirectories(featureDir);
        Files.createDirectories(featureDir.resolve("adrs"));

        SkillMetadata md = loader.describe(skillName);
        Path outputFile = PathSecurity.resolveContained(
            featureDir, md.outputRelativePath(), "output");
        Path completionFile = md.completionRelativePath() != null
            ? PathSecurity.resolveContained(featureDir, md.completionRelativePath(), "completion")
            : null;
        boolean updateMode = Files.exists(outputFile);

        SkillContext ctx = new SkillContext(safeFeature, featureDir,
            (seedFile != null && Files.exists(seedFile)) ? seedFile : null,
            updateMode);
        Skill skill = loader.load(skillName, ctx);

        AgentSpec spec  = AgentRegistry.resolve(config);
        String   model  = modelOverride != null && !modelOverride.isBlank()
            ? modelOverride
            : config.modelFor(skillName);

        printBanner(skill, safeFeature, outputFile, spec, model, updateMode);

        Path transcriptFile = transcriptPathFor(featureDir, md);
        Transcript transcript = new Transcript(transcriptFile);
        transcript.recordHeader(skillName, safeFeature);

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
                outputFile, completionFile, transcript, null);

            ChatLoop.Outcome outcome = loop.run(skill.body());
            printFooter(outcome, outputFile, transcriptFile);
            return outcome;
        }
    }

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

    private static void selectModel(AcpSyncClient client, String sessionId,
                                     SessionModelState modelState, String want) {
        if (want == null || want.isBlank()) return;
        if (modelState == null || modelState.availableModels() == null
                || modelState.availableModels().isEmpty()) {
            System.out.println(Ansi.dim("[model] agent advertised no models — ignoring '" + want + "'"));
            return;
        }
        ModelInfo match = AgentRegistry.findModel(modelState.availableModels(), want);
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
}
