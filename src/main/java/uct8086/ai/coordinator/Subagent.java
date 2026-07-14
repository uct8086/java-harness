package uct8086.ai.coordinator;

import uct8086.ai.common.enums.AgentRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a subagent in the multi-agent coordination system.
 * Maps to OpenHarness's Subagent Spawning & Delegation.
 */
public record Subagent(
        String id,
        String name,
        AgentRole role,
        String systemPrompt,
        String status,
        Instant createdAt
) {
    public Subagent(String name, AgentRole role, String systemPrompt) {
        this(UUID.randomUUID().toString(), name, role, systemPrompt,
             "created", Instant.now());
    }

    public Subagent withStatus(String newStatus) {
        return new Subagent(id, name, role, systemPrompt, newStatus, createdAt);
    }
}
