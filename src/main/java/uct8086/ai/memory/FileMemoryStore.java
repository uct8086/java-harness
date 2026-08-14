package uct8086.ai.memory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uct8086.ai.core.config.HarnessProperties;

/**
 * File-based implementation of MemoryStore.
 * Maps to OpenHarness's MEMORY.md approach.
 *
 * <p>Memory is isolated per user: each user has their own directory
 * ({@code .uct8086/memory/{userId}/MEMORY.md}) and in-memory index. Persists
 * entries to a markdown file and maintains an in-memory index for fast lookups.
 *
 * <p><b>Deprecated</b>: superseded by {@link MySqlMemoryStore}. This class is
 * intentionally NOT registered as a Spring bean (no {@code @Component}) to avoid
 * clashing with the MySQL-backed implementation. Kept for reference/fallback.
 */
public class FileMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(FileMemoryStore.class);

    private final Map<Long, Map<String, MemoryEntry>> entriesByUser = new ConcurrentHashMap<>();
    private final Path memoryRoot;

    public FileMemoryStore(HarnessProperties properties) {
        this(resolveDefaultMemoryRoot(properties));
    }

    public FileMemoryStore(Path memoryRoot) {
        this.memoryRoot = memoryRoot;
    }

    /**
     * Resolve the memory root directory: explicit config wins, otherwise default to
     * <workingDirectory>/.uct8086/memory.
     */
    private static Path resolveDefaultMemoryRoot(HarnessProperties properties) {
        String configured = properties.getMemoryFile();
        if (configured != null && !configured.isBlank()) {
            // If a file is explicitly configured, use its parent as the root.
            return Path.of(configured).getParent();
        }
        String workingDir = properties.getWorkingDirectory() != null
                ? properties.getWorkingDirectory()
                : System.getProperty("user.dir");
        return Path.of(workingDir, ".uct8086", "memory");
    }

    private Path memoryFileFor(Long userId) {
        return memoryRoot.resolve(String.valueOf(userId)).resolve("MEMORY.md");
    }

    private Map<String, MemoryEntry> entriesFor(Long userId) {
        return entriesByUser.computeIfAbsent(userId, k -> {
            Map<String, MemoryEntry> map = new ConcurrentHashMap<>();
            load(userId, map);
            return map;
        });
    }

    @Override
    public MemoryEntry save(Long userId, MemoryEntry entry) {
        entriesFor(userId).put(entry.id(), entry);
        persist(userId);
        log.debug("Saved memory entry: {} ({}) for user {}", entry.id(), entry.category(), userId);
        return entry;
    }

    @Override
    public Optional<MemoryEntry> get(Long userId, String id) {
        return Optional.ofNullable(entriesFor(userId).get(id));
    }

    @Override
    public List<MemoryEntry> getByCategory(Long userId, String category) {
        return entriesFor(userId).values().stream()
                .filter(e -> e.category().equals(category))
                .sorted(Comparator.comparing(MemoryEntry::createdAt))
                .toList();
    }

    @Override
    public List<MemoryEntry> getAll(Long userId) {
        return entriesFor(userId).values().stream()
                .sorted(Comparator.comparing(MemoryEntry::createdAt))
                .toList();
    }

    @Override
    public MemoryEntry update(Long userId, MemoryEntry entry) {
        entriesFor(userId).put(entry.id(), entry);
        persist(userId);
        return entry;
    }

    @Override
    public boolean delete(Long userId, String id) {
        MemoryEntry removed = entriesFor(userId).remove(id);
        if (removed != null) {
            persist(userId);
            log.debug("Deleted memory entry: {} for user {}", id, userId);
            return true;
        }
        return false;
    }

    @Override
    public List<MemoryEntry> search(Long userId, String keyword) {
        String lower = keyword.toLowerCase();
        return entriesFor(userId).values().stream()
                .filter(e -> e.content().toLowerCase().contains(lower) ||
                             e.category().toLowerCase().contains(lower))
                .sorted(Comparator.comparing(MemoryEntry::createdAt))
                .toList();
    }

    @Override
    public void clear(Long userId) {
        Map<String, MemoryEntry> map = entriesByUser.get(userId);
        if (map != null) {
            map.clear();
        }
        persist(userId);
        log.info("Memory cleared for user {}", userId);
    }

    private void persist(Long userId) {
        Path memoryFile = memoryFileFor(userId);
        // Serialize file writes to avoid concurrent interleaving/corruption, and use
        // atomic write (temp file + rename) so readers never observe partial content.
        synchronized (this) {
            try {
                Path parent = memoryFile.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }

                StringBuilder sb = new StringBuilder();
                sb.append("# UCT8086 Memory\n\n");

                Map<String, List<MemoryEntry>> byCategory = entriesFor(userId).values().stream()
                        .collect(Collectors.groupingBy(MemoryEntry::category));

                for (var entry : byCategory.entrySet()) {
                    sb.append("## ").append(entry.getKey()).append("\n\n");
                    for (MemoryEntry mem : entry.getValue()) {
                        sb.append("- **").append(mem.id()).append("**: ")
                          .append(mem.content())
                          .append(" (").append(mem.createdAt()).append(")\n");
                    }
                    sb.append("\n");
                }

                Path tempFile = memoryFile.resolveSibling(memoryFile.getFileName() + ".tmp");
                Files.writeString(tempFile, sb.toString());
                try {
                    Files.move(tempFile, memoryFile,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tempFile, memoryFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.error("Failed to persist memory for user {} to {}", userId, memoryFile, e);
            }
        }
    }

    private void load(Long userId, Map<String, MemoryEntry> target) {
        Path memoryFile = memoryFileFor(userId);
        if (!Files.exists(memoryFile)) {
            log.info("Memory file does not exist for user {}: {}", userId, memoryFile);
            return;
        }

        try {
            String content = Files.readString(memoryFile);
            String[] lines = content.split("\n");
            String currentCategory = "general";
            for (String line : lines) {
                if (line.startsWith("## ")) {
                    currentCategory = line.substring(3).trim();
                } else if (line.startsWith("- **")) {
                    int endId = line.indexOf("**:", 4);
                    if (endId > 0) {
                        String id = line.substring(4, endId).trim();
                        String rest = line.substring(endId + 3).trim();
                        int tsStart = rest.lastIndexOf(" (");
                        String memContent = tsStart > 0 ? rest.substring(0, tsStart).trim() : rest;
                        target.put(id, new MemoryEntry(id, currentCategory, memContent, Instant.now(), Instant.now()));
                    }
                }
            }
            log.info("Loaded {} memory entries for user {} from {}", target.size(), userId, memoryFile);
        } catch (IOException e) {
            log.error("Failed to load memory for user {} from {}", userId, memoryFile, e);
        }
    }
}
