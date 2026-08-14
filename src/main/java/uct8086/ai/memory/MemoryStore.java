package uct8086.ai.memory;

import java.util.List;
import java.util.Optional;

/**
 * Interface for persistent memory storage.
 * Maps to OpenHarness's Memory system.
 *
 * <p>All operations are scoped to a {@code userId}, so each user's memory is
 * fully isolated from others.
 */
public interface MemoryStore {

    /**
     * Save a memory entry for the given user.
     */
    MemoryEntry save(Long userId, MemoryEntry entry);

    /**
     * Get a memory entry by ID for the given user.
     */
    Optional<MemoryEntry> get(Long userId, String id);

    /**
     * Get all memory entries for a category belonging to the given user.
     */
    List<MemoryEntry> getByCategory(Long userId, String category);

    /**
     * Get all memory entries for the given user.
     */
    List<MemoryEntry> getAll(Long userId);

    /**
     * Update a memory entry for the given user.
     */
    MemoryEntry update(Long userId, MemoryEntry entry);

    /**
     * Delete a memory entry by ID for the given user.
     */
    boolean delete(Long userId, String id);

    /**
     * Search memory entries by keyword for the given user.
     */
    List<MemoryEntry> search(Long userId, String keyword);

    /**
     * Clear all memory entries for the given user.
     */
    void clear(Long userId);
}
