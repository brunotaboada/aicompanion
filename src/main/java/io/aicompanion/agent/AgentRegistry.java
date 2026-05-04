package io.aicompanion.agent;

import io.aicompanion.config.Config;
import java.io.File;
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
                "claude-agent-acp, codex-acp, gemini, copilot, opencode"));
    }

    public static List<AgentSpec> detectAll() {
        return AgentSpec.ALL.stream().filter(AgentRegistry::isInstalled).toList();
    }

    public static boolean isInstalled(AgentSpec spec) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return false;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (new File(dir, spec.executable()).canExecute()) return true;
        }
        return false;
    }
}
