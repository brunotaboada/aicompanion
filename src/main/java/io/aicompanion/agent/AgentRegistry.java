package io.aicompanion.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema.ModelInfo;
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

    /**
     * Resolve a user-supplied model token against the agent's advertised list.
     * Priority: exact id → exact name → partial match (most specific id wins).
     */
    public static ModelInfo findModel(List<ModelInfo> models, String want) {
        String w = want.toLowerCase().trim();
        for (ModelInfo m : models) {
            if (m.modelId().equalsIgnoreCase(want)) return m;
        }
        for (ModelInfo m : models) {
            if (m.name() != null && m.name().equalsIgnoreCase(want)) return m;
        }
        ModelInfo best = null;
        for (ModelInfo m : models) {
            String id   = m.modelId().toLowerCase();
            String name = m.name() == null ? "" : m.name().toLowerCase();
            if (id.contains(w) || name.contains(w)) {
                if (best == null || m.modelId().length() > best.modelId().length()) {
                    best = m;
                }
            }
        }
        return best;
    }

    /** Render "{id} ({name})" — or just the id if no human name is available. */
    public static String modelLabel(ModelInfo m) {
        String id   = m.modelId();
        String name = m.name();
        if (name == null || name.isBlank() || name.equalsIgnoreCase(id)) return id;
        return id + " (" + name + ")";
    }
}
