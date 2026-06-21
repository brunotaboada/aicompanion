package io.aicompanion.agent;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionCancelled;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdateNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileResponse;
import io.aicompanion.AgentConsole;
import io.aicompanion.config.Config;
import io.aicompanion.console.Ansi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Builds an {@link AcpSyncClient} with the read/write/permission handlers and
 * session-update consumer shared by every runner — {@code TaskRunner} for
 * autonomous task execution and the upcoming {@code SkillRunner} for
 * interactive PRD / TechSpec / task-decomposition chats.
 *
 * Both modes need the same filesystem access (serve any read, accept any write)
 * and the same permission auto-approval (prefer {@code ALLOW_ALWAYS}). They
 * also share the streaming-output story: chunks land in {@link AgentConsole},
 * thoughts and tool calls log when enabled in {@link Config}.
 */
public final class AcpClientFactory {

    private AcpClientFactory() {}

    public static AcpSyncClient build(StdioAcpClientTransport transport,
                                       Config config,
                                       AgentConsole console) {
        return AcpClient.sync(transport)
            .requestTimeout(Duration.ofMinutes(config.sessionTimeoutMin()))

            // Stream every update; buffer the text chunks for the summary log
            .sessionUpdateConsumer(notification -> {
                var update = notification.update();
                if (update instanceof AgentMessageChunk msg) {
                    console.printAgentChunk(((TextContent) msg.content()).text());
                    return;
                }
                // Anything that is not visible agent text interrupts the current
                // text block: tool calls, tool-call updates, plans, available-command
                // lists, thoughts. Mark a break so the next visible text block is
                // separated (a stray space) instead of gluing onto the previous one
                // — "…interview." + "Let me…" → "…interview. Let me…". Idempotent,
                // and a no-op before any text has streamed.
                console.markTextBlockBreak();
                if (update instanceof AgentThoughtChunk thought) {
                    if (config.logThoughts()) {
                        console.logEvent(Ansi.dim("[working] "
                            + ((TextContent) thought.content()).text().trim()));
                    }
                } else if (update instanceof ToolCall tc) {
                    if (config.logToolCalls()) {
                        console.logEvent(Ansi.dim("[tool:" + tc.kind() + "] " + tc.title()));
                    }
                } else if (update instanceof ToolCallUpdateNotification tcu) {
                    if (config.logToolCalls()) {
                        console.logEvent(Ansi.dim("[tool:" + tcu.toolCallId() + "] → " + tcu.status()));
                    }
                }
            })

            // Serve any file the agent wants to read
            .readTextFileHandler(req -> {
                if (config.logToolCalls()) console.logEvent(Ansi.dim("[read ] " + req.path()));
                try {
                    return new ReadTextFileResponse(Files.readString(Path.of(req.path())));
                } catch (IOException e) {
                    throw new RuntimeException(
                        "Cannot read file: " + req.path() + " — " + e.getMessage(), e);
                }
            })

            // Write any file the agent produces
            .writeTextFileHandler(req -> {
                if (config.logToolCalls()) console.logEvent(Ansi.dim("[write] " + req.path()
                    + " (" + req.content().length() + " chars)"));
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
                console.logEvent(Ansi.dim("[perm ] auto-approved: " + tool));
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
}
