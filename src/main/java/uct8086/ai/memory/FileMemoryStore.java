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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uct8086.ai.core.config.HarnessProperties;

/**
 * File-based implementation of MemoryStore.
 * Maps to OpenHarness's MEMORY.md approach.
 *
 * <p>Persists memory entries to a markdown file (MEMORY.md) and
 * maintains an in-memory index for fast lookups.
 */
@Component
public class FileMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(FileMemoryStore.class);

    private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();
    private final Path memoryFile;

    @Autowired
    public FileMemoryStore(HarnessProperties properties) {
        this(resolveDefaultMemoryFile(properties));
    }

    public FileMemoryStore(Path memoryFile) {
        this.memoryFile = memoryFile;
        load();
    }

    /**
     * Resolve the memory file path: explicit config wins, otherwise default to
     * <workingDirectory>/.uct8086/MEMORY.md so the file lives in the project.
     */
    private static Path resolveDefaultMemoryFile(HarnessProperties properties) {
        String configured = properties.getMemoryFile();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String workingDir = properties.getWorkingDirectory() != null
                ? properties.getWorkingDirectory()
                : System.getProperty("user.dir");
        return Path.of(workingDir, ".uct8086", "MEMORY.md");
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        entries.put(entry.id(), entry);
        persist();
        log.debug("Saved memory entry: {} ({})", entry.id(), entry.category());
        return entry;
    }

    @Override
    public Optional<MemoryEntry> get(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> getByCategory(String category) {
        return entries.values().stream()
                .filter(e -> e.category().equals(category))
                .sorted(Comparator.comparing(MemoryEntry::createdAt))
                .toList();
    }

    @Override
    public List<MemoryEntry> getAll() {
        return entries.values().stream()
                .sorted(Comparator.comparing(MemoryEntry::createdAt))
                .toList();
    }

    @Override
    public MemoryEntry update(MemoryEntry entry) {
        entries.put(entry.id(), entry);
        persist();
        return entry;
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            persist();
            log.debug("Deleted memory entry: {}", id);
            return true;
        }
        return false;
    }

    @Override
    public List<MemoryEntry> search(String keyword) {
        String lower = keyword.toLowerCase();
        return entries.values().stream()
                .filter(e -> e.content().toLowerCase().contains(lower) ||
                             e.category().toLowerCase().contains(lower))
                .sorted(Comparator.comparing(MemoryEntry::createdAt))
                .toList();
    }

    @Override
    public void clear() {
        entries.clear();
        persist();
        log.info("Memory cleared");
    }

    private void persist() {
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

                Map<String, List<MemoryEntry>> byCategory = entries.values().stream()
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
                log.error("Failed to persist memory to {}", memoryFile, e);
            }
        }
    }

    private void load() {
        if (!Files.exists(memoryFile)) {
            log.info("Memory file does not exist, creating: {}", memoryFile);
            try {
                Path parent = memoryFile.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                Files.writeString(memoryFile, "# UCT8086 Memory\n\n");
            } catch (IOException e) {
                log.warn("Failed to create memory file: {}", memoryFile, e);
            }
            return;
        }

        try {
            String content = Files.readString(memoryFile);
            // Simple parsing - for production, use a proper markdown parser
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
                        // Remove timestamp part
                        int tsStart = rest.lastIndexOf(" (");
                        String memContent = tsStart > 0 ? rest.substring(0, tsStart).trim() : rest;
                        entries.put(id, new MemoryEntry(id, currentCategory, memContent, Instant.now(), Instant.now()));
                    }
                }
            }
            log.info("Loaded {} memory entries from {}", entries.size(), memoryFile);
        } catch (IOException e) {
            log.error("Failed to load memory from {}", memoryFile, e);
        }
    }
}
