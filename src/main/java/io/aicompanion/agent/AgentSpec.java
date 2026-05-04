package io.aicompanion.agent;

import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import io.aicompanion.config.Config;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public record AgentSpec(
    String id,
    String executable,
    BiFunction<Boolean, List<String>, AgentParameters> paramsBuilder
) {
    public Supplier<AgentParameters> params(Config config) {
        return () -> paramsBuilder.apply(config.yolo(), config.agentExtraArgs());
    }

    public static final List<AgentSpec> ALL = List.of(
        new AgentSpec("claude", "claude-agent-acp",
            (yolo, extra) -> {
                var b = AgentParameters.builder("claude-agent-acp");
                if (yolo) b.arg("--yolo");
                extra.forEach(b::arg);
                return b.build();
            }),
        new AgentSpec("codex", "codex-acp",
            (yolo, extra) -> {
                var b = AgentParameters.builder("codex-acp");
                if (yolo) b.arg("--yolo");
                extra.forEach(b::arg);
                return b.build();
            }),
        new AgentSpec("gemini", "gemini",
            (yolo, extra) -> {
                var b = AgentParameters.builder("gemini").arg("--experimental-acp");
                if (yolo) b.arg("--yolo");
                extra.forEach(b::arg);
                return b.build();
            }),
        new AgentSpec("copilot", "copilot",
            (yolo, extra) -> {
                var b = AgentParameters.builder("copilot").arg("--acp");
                if (yolo) b.arg("--yolo");
                extra.forEach(b::arg);
                return b.build();
            }),
        new AgentSpec("opencode", "opencode",
            (yolo, extra) -> {
                var b = AgentParameters.builder("opencode").arg("acp");
                extra.forEach(b::arg);
                return b.build();
            })
    );
}
