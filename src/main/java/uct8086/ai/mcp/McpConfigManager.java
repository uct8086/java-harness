package uct8086.ai.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uct8086.ai.common.model.McpServerConfig;
import uct8086.ai.core.config.HarnessProperties;

/**
 * Manages MCP server configurations with JSON file persistence, scoped per user.
 *
 * <p>Each user's configs are stored in their own file
 * {@code .uct8086/mcp-servers/{userId}.json} under the working directory. This
 * isolates MCP server configurations between users.
 */
@Component
public class McpConfigManager {

    private static final Logger log = LoggerFactory.getLogger(McpConfigManager.class);

    // userId -> (configId -> config)
    private final Map<Long, Map<String, McpServerConfig>> configsByUser = new ConcurrentHashMap<>();
    private final Path configRoot;
    private final ObjectMapper mapper;

    @Autowired
    public McpConfigManager(HarnessProperties properties) {
        String workingDir = properties.getWorkingDirectory() != null
                ? properties.getWorkingDirectory()
                : System.getProperty("user.dir");
        this.configRoot = Path.of(workingDir, ".uct8086", "mcp-servers");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ---- CRUD (scoped by userId) ----

    public List<McpServerConfig> listAll(Long userId) {
        Map<String, McpServerConfig> configs = configsFor(userId);
        return configs.values().stream()
                .sorted(Comparator.comparing(McpServerConfig::name))
                .toList();
    }

    public Optional<McpServerConfig> get(Long userId, String id) {
        Map<String, McpServerConfig> configs = configsByUser.get(userId);
        return configs != null ? Optional.ofNullable(configs.get(id)) : Optional.empty();
    }

    public McpServerConfig save(Long userId, McpServerConfig config) {
        configsFor(userId).put(config.id(), config);
        persist(userId);
        log.info("Saved MCP server config for user {}: {} ({})", userId, config.name(), config.id());
        return config;
    }

    public boolean delete(Long userId, String id) {
        Map<String, McpServerConfig> configs = configsByUser.get(userId);
        if (configs == null) {
            return false;
        }
        McpServerConfig removed = configs.remove(id);
        if (removed != null) {
            persist(userId);
            log.info("Deleted MCP server config for user {}: {} ({})", userId, removed.name(), removed.id());
            return true;
        }
        return false;
    }

    // ---- Persistence ----

    private Map<String, McpServerConfig> configsFor(Long userId) {
        return configsByUser.computeIfAbsent(userId, k -> {
            Map<String, McpServerConfig> map = new ConcurrentHashMap<>();
            load(userId, map);
            return map;
        });
    }

    private Path configFileFor(Long userId) {
        return configRoot.resolve(userId + ".json");
    }

    private void persist(Long userId) {
        // Serialize writes and use atomic write (temp file + rename) to avoid
        // concurrent interleaving and partial-read corruption.
        synchronized (this) {
            try {
                Path configFile = configFileFor(userId);
                Path parent = configFile.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                Map<String, McpServerConfig> configs = configsByUser.get(userId);
                List<McpServerConfig> list = configs != null ? new ArrayList<>(configs.values()) : List.of();
                Path tempFile = configFile.resolveSibling(configFile.getFileName() + ".tmp");
                mapper.writeValue(tempFile.toFile(), list);
                try {
                    Files.move(tempFile, configFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(tempFile, configFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.error("Failed to persist MCP configs for user {}", userId, e);
            }
        }
    }

    private void load(Long userId, Map<String, McpServerConfig> target) {
        Path configFile = configFileFor(userId);
        if (!Files.exists(configFile)) {
            log.debug("No MCP config file for user {}: {}", userId, configFile);
            return;
        }
        try {
            List<McpServerConfig> list = mapper.readValue(
                    configFile.toFile(),
                    new TypeReference<List<McpServerConfig>>() {});
            list.forEach(c -> target.put(c.id(), c));
            log.info("Loaded {} MCP server configs for user {} from {}", target.size(), userId, configFile);
        } catch (IOException e) {
            log.error("Failed to load MCP configs for user {} from {}", userId, configFile, e);
        }
    }
}
