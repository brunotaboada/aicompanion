package io.aicompanion.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema.ModelInfo;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentRegistryModelTest {

    private static ModelInfo model(String id, String name) {
        return new ModelInfo(id, name, null);
    }

    // ── findModel ────────────────────────────────────────────────────────────

    @Test
    void exactIdMatch() {
        ModelInfo m = model("claude-sonnet-4-6", "Claude Sonnet");
        assertEquals(m, AgentRegistry.findModel(List.of(m), "claude-sonnet-4-6"));
    }

    @Test
    void exactIdMatchIsCaseInsensitive() {
        ModelInfo m = model("claude-sonnet-4-6", "Claude Sonnet");
        assertEquals(m, AgentRegistry.findModel(List.of(m), "CLAUDE-SONNET-4-6"));
    }

    @Test
    void exactNameMatch() {
        ModelInfo m = model("claude-sonnet-4-6", "Claude Sonnet");
        assertEquals(m, AgentRegistry.findModel(List.of(m), "Claude Sonnet"));
    }

    @Test
    void partialMatchOnId() {
        ModelInfo m = model("claude-sonnet-4-6", "Claude Sonnet");
        assertEquals(m, AgentRegistry.findModel(List.of(m), "sonnet"));
    }

    @Test
    void partialMatchOnName() {
        ModelInfo m = model("claude-sonnet-4-6", "Claude Sonnet");
        assertEquals(m, AgentRegistry.findModel(List.of(m), "Sonnet"));
    }

    @Test
    void partialMatchPrefersLongerModelId() {
        ModelInfo shorter = model("claude-sonnet",     "Sonnet Base");
        ModelInfo longer  = model("claude-sonnet-4-6", "Sonnet Latest");
        assertEquals(longer, AgentRegistry.findModel(List.of(shorter, longer), "sonnet"));
    }

    @Test
    void returnsNullWhenNoMatch() {
        ModelInfo m = model("claude-sonnet-4-6", "Claude Sonnet");
        assertNull(AgentRegistry.findModel(List.of(m), "gemini"));
    }

    @Test
    void returnsNullForEmptyList() {
        assertNull(AgentRegistry.findModel(List.of(), "sonnet"));
    }

    @Test
    void exactIdBeatsPartialNameMatch() {
        ModelInfo exactId  = model("sonnet",           null);
        ModelInfo partial  = model("claude-sonnet-4-6","Claude Sonnet");
        assertEquals(exactId, AgentRegistry.findModel(List.of(partial, exactId), "sonnet"));
    }

    @Test
    void nullNameDoesNotCrash() {
        ModelInfo m = model("claude-opus-4-8", null);
        assertEquals(m, AgentRegistry.findModel(List.of(m), "opus"));
    }

    // ── modelLabel ───────────────────────────────────────────────────────────

    @Test
    void labelWithDistinctName() {
        ModelInfo m = model("claude-sonnet-4-6", "Claude Sonnet");
        assertEquals("claude-sonnet-4-6 (Claude Sonnet)", AgentRegistry.modelLabel(m));
    }

    @Test
    void labelWithNullName() {
        ModelInfo m = model("claude-sonnet-4-6", null);
        assertEquals("claude-sonnet-4-6", AgentRegistry.modelLabel(m));
    }

    @Test
    void labelWithBlankName() {
        ModelInfo m = model("claude-sonnet-4-6", "  ");
        assertEquals("claude-sonnet-4-6", AgentRegistry.modelLabel(m));
    }

    @Test
    void labelWhenNameEqualsId() {
        ModelInfo m = model("claude-sonnet-4-6", "claude-sonnet-4-6");
        assertEquals("claude-sonnet-4-6", AgentRegistry.modelLabel(m));
    }

    @Test
    void labelWhenNameEqualsIdCaseInsensitive() {
        ModelInfo m = model("claude-sonnet-4-6", "CLAUDE-SONNET-4-6");
        assertEquals("claude-sonnet-4-6", AgentRegistry.modelLabel(m));
    }
}
