package io.aicompanion.agent;

import io.aicompanion.config.Config;
import io.aicompanion.util.Platform;
import java.util.List;

public class AgentRegistry {

    public static AgentSpec resolve(Config config) {
        if (config.agent() != null && !config.agent().isBlank()) {
            return AgentSpec.ALL.stream()
                .filter(s -> s.id().equalsIgnoreCase(config.agent()))
                .filter(AgentRegistry::isInstalled)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Configured agent '" + config.agent() + "' is not installed on PATH."));
        }
        return AgentSpec.ALL.stream()
            .filter(AgentRegistry::isInstalled)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No AI agent found on PATH. Install one of: " +
                "claude-code-acp, codex-acp, gemini, copilot, opencode"));
    }

    public static List<AgentSpec> detectAll() {
        return AgentSpec.ALL.stream().filter(AgentRegistry::isInstalled).toList();
    }

    public static boolean isInstalled(AgentSpec spec) {
        return Platform.findOnPath(spec.executable()) != null;
    }
}
