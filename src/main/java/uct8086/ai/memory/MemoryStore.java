package uct8086.ai.memory;

import java.util.List;
import java.util.Optional;

/**
 * Interface for persistent memory storage.
 * Maps to OpenHarness's Memory system.
 */
public interface MemoryStore {

    /**
     * Save a memory entry.
     */
    MemoryEntry save(MemoryEntry entry);

    /**
     * Get a memory entry by ID.
     */
    Optional<MemoryEntry> get(String id);

    /**
     * Get all memory entries for a category.
     */
    List<MemoryEntry> getByCategory(String category);

    /**
     * Get all memory entries.
     */
    List<MemoryEntry> getAll();

    /**
     * Update a memory entry.
     */
    MemoryEntry update(MemoryEntry entry);

    /**
     * Delete a memory entry by ID.
     */
    boolean delete(String id);

    /**
     * Search memory entries by keyword.
     */
    List<MemoryEntry> search(String keyword);

    /**
     * Clear all memory entries.
     */
    void clear();
}
