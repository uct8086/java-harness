package uct8086.ai.common.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a conversation session.
 */
public record SessionInfo(
        String id,
        String name,
        Instant createdAt,
        Instant updatedAt,
        int messageCount
) {
    public SessionInfo() {
        this(UUID.randomUUID().toString(), null, Instant.now(), Instant.now(), 0);
    }

    public SessionInfo(String name) {
        this(UUID.randomUUID().toString(), name, Instant.now(), Instant.now(), 0);
    }
}
