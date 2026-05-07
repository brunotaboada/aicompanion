package io.aicompanion;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionCancelled;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdateNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileResponse;
import io.aicompanion.agent.AgentRegistry;
import io.aicompanion.agent.AgentSpec;
import io.aicompanion.config.Config;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class TaskRunner {

    private final Config config;

    /**
     * Holds the in-progress task's text output.
     * AtomicReference lets the sessionUpdateConsumer lambda (created once at
     * client-build time) write into whichever StringBuilder is current.
     */
    private final AtomicReference<StringBuilder> summaryBuf =
        new AtomicReference<>(new StringBuilder());

    public TaskRunner(Config config) {
        this.config = config;
    }

    public void run() throws Exception {
        AgentSpec spec    = AgentRegistry.resolve(config);
        Path      projDir = Path.of(config.projectDir()).toAbsolutePath();
        var       verifier = new TestVerifier(config.testCommand(), projDir);
        var       reporter = new Reporter(config);

        // Collect sorted paths only — content is NOT read until each task's turn
        List<Path> taskPaths = resolveTaskPaths();
        int total = taskPaths.size();

        if (total == 0) {
            System.out.println("No task files found in: " + config.tasksDir());
            return;
        }

        System.out.println("Agent  : " + spec.id());
        System.out.println("Tasks  : " + total);
        System.out.println("Dir    : " + projDir);
        System.out.println();

        var transport = new StdioAcpClientTransport(spec.params(config).get());

        try (AcpSyncClient client = buildClient(transport)) {

            // ACP handshake — declare full filesystem capability
            client.initialize(new InitializeRequest(1,
                new ClientCapabilities(new FileSystemCapability(true, true), false)));

            var session = client.newSession(
                new NewSessionRequest(projDir.toString(), List.of()));
            System.out.println("Session: " + session.sessionId() + "\n");

            for (int i = 0; i < total; i++) {
                Path   taskPath = taskPaths.get(i);
                String taskName = taskPath.getFileName().toString();

                System.out.println("─".repeat(60));
                System.out.printf("Task %d/%d: %s%n", i + 1, total, taskName);
                System.out.println("─".repeat(60));

                // Reset buffer, then read this task's file lazily
                summaryBuf.set(new StringBuilder());
                String taskContent = Files.readString(taskPath);

                // Instruct the agent to finish with a concise summary only
                String prompt = taskContent + "\n\n" +
                    "When you are done, output ONLY a concise summary " +
                    "(3-5 bullet points) of exactly what you changed or created. " +
                    "Do not repeat any code.";

                var response = client.prompt(new PromptRequest(
                    session.sessionId(),
                    List.of(new TextContent(prompt))));

                System.out.println("\n[stop] " + response.stopReason());

                // Write the summary to the log file
                reporter.write(taskName, summaryBuf.get().toString());

                if (config.testEnabled()) {
                    System.out.println("\nRunning tests...");
                    TestVerifier.Result result = verifier.run();

                    int attempt = 0;
                    while (!result.passed() && attempt < config.maxFixAttempts()) {
                        attempt++;
                        System.out.printf("✗ Tests FAILED — fix attempt %d/%d%n",
                            attempt, config.maxFixAttempts());
                        System.out.println(result.output());

                        String fixPrompt =
                            "Your previous changes broke the test suite. " +
                            "The test command `" + config.testCommand() + "` failed " +
                            "with the output below.\n\n" +
                            "----- TEST OUTPUT -----\n" +
                            result.output() +
                            "\n----- END OUTPUT -----\n\n" +
                            "Diagnose the failure, fix it (edit existing code, do not " +
                            "delete or weaken the failing tests), then output ONLY a " +
                            "concise summary (3-5 bullets) of what you changed.";

                        summaryBuf.set(new StringBuilder());
                        var fixResp = client.prompt(new PromptRequest(
                            session.sessionId(),
                            List.of(new TextContent(fixPrompt))));
                        System.out.println("\n[stop] " + fixResp.stopReason());

                        reporter.write(taskName + ".fix" + attempt,
                            summaryBuf.get().toString());

                        System.out.println("\nRe-running tests...");
                        result = verifier.run();
                    }

                    if (result.passed()) {
                        System.out.println(attempt == 0
                            ? "✓ Tests passed\n"
                            : "✓ Tests passed after " + attempt + " fix attempt(s)\n");
                    } else {
                        System.out.printf("✗ Tests still FAILED after %d fix attempt(s)%n",
                            config.maxFixAttempts());
                        System.out.println(result.output());
                        if (config.stopOnFailure()) {
                            System.out.println("Stopping — fix the failure before continuing.");
                            return;
                        }
                    }
                }
            }

            System.out.println("─".repeat(60));
            System.out.println("All " + total + " tasks complete.");
        }
    }

    private AcpSyncClient buildClient(StdioAcpClientTransport transport) {
        return AcpClient.sync(transport)
            .requestTimeout(Duration.ofMinutes(config.sessionTimeoutMin()))

            // Stream every update; buffer the text chunks for the summary log
            .sessionUpdateConsumer(notification -> {
                var update = notification.update();
                if (update instanceof AgentMessageChunk msg) {
                    String text = ((TextContent) msg.content()).text();
                    System.out.print(text);
                    summaryBuf.get().append(text);
                } else if (update instanceof AgentThoughtChunk thought
                        && config.logThoughts()) {
                    System.out.println("\n[thinking] "
                        + ((TextContent) thought.content()).text().trim());
                } else if (update instanceof ToolCall tc
                        && config.logToolCalls()) {
                    System.out.println("\n[tool:" + tc.kind() + "] " + tc.title());
                } else if (update instanceof ToolCallUpdateNotification tcu
                        && config.logToolCalls()) {
                    System.out.println("[tool:" + tcu.toolCallId() + "] → " + tcu.status());
                }
            })

            // Serve any file the agent wants to read
            .readTextFileHandler(req -> {
                if (config.logToolCalls()) System.out.println("[read ] " + req.path());
                try {
                    return new ReadTextFileResponse(Files.readString(Path.of(req.path())));
                } catch (IOException e) {
                    throw new RuntimeException(
                        "Cannot read file: " + req.path() + " — " + e.getMessage(), e);
                }
            })

            // Write any file the agent produces
            .writeTextFileHandler(req -> {
                if (config.logToolCalls())
                    System.out.println("[write] " + req.path()
                        + " (" + req.content().length() + " chars)");
                try {
                    Path p = Path.of(req.path());
                    if (p.getParent() != null) Files.createDirectories(p.getParent());
                    Files.writeString(p, req.content());
                    return new WriteTextFileResponse();
                } catch (IOException e) {
                    throw new RuntimeException(
                        "Cannot write file: " + req.path() + " — " + e.getMessage(), e);
                }
            })

            // Auto-approve every permission request — prefer ALLOW_ALWAYS
            .requestPermissionHandler(req -> {
                String tool = req.toolCall() != null ? req.toolCall().title() : "unknown";
                System.out.println("[perm ] auto-approved: " + tool);
                List<PermissionOption> opts = req.options();
                String chosen = opts.stream()
                    .filter(o -> o.kind() != null
                        && o.kind().name().contains("ALLOW_ALWAYS"))
                    .map(PermissionOption::optionId)
                    .findFirst()
                    .orElse(opts.isEmpty() ? null : opts.get(0).optionId());
                return chosen != null
                    ? new RequestPermissionResponse(new PermissionSelected(chosen))
                    : new RequestPermissionResponse(new PermissionCancelled());
            })

            .build();
    }

    /** Returns sorted paths only — no file content is read here. */
    List<Path> resolveTaskPaths() throws IOException {
        Path dir = Path.of(config.tasksDir());
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException(
                "Tasks directory not found: " + dir.toAbsolutePath());
        }
        Comparator<Path> order = "none".equalsIgnoreCase(config.taskSort())
            ? Comparator.comparing(p -> 0)
            : Comparator.comparing(p -> p.getFileName().toString());

        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> {
                    String name = p.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    String ext = dot >= 0 ? name.substring(dot + 1) : "";
                    return config.taskExtensions().contains(ext);
                })
                .sorted(order)
                .toList();
        }
    }
}
