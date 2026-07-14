package uct8086.ai.memory;

import java.time.Instant;
import java.util.List;

/**
 * A memory entry in the persistent memory system.
 * Maps to OpenHarness's MEMORY.md Persistent Memory feature.
 */
public record MemoryEntry(
        String id,
        String category,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
    public MemoryEntry(String category, String content) {
        this(java.util.UUID.randomUUID().toString(), category, content, Instant.now(), Instant.now());
    }

    public MemoryEntry update(String newContent) {
        return new MemoryEntry(id, category, newContent, createdAt, Instant.now());
    }
}
