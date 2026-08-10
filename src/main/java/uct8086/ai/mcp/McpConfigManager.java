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
 * Manages MCP server configurations with JSON file persistence.
 *
 * <p>Configs are stored in {@code .uct8086/mcp-servers.json} under
 * the working directory. Spring AI 2.0 auto-wires MCP ToolCallbacks
 * from {@code application.yml} at startup; this manager stores the
 * user-facing configuration that maps to those entries.
 */
@Component
public class McpConfigManager {

    private static final Logger log = LoggerFactory.getLogger(McpConfigManager.class);

    private final Map<String, McpServerConfig> configs = new ConcurrentHashMap<>();
    private final Path configFile;
    private final ObjectMapper mapper;

    @Autowired
    public McpConfigManager(HarnessProperties properties) {
        String workingDir = properties.getWorkingDirectory() != null
                ? properties.getWorkingDirectory()
                : System.getProperty("user.dir");
        this.configFile = Path.of(workingDir, ".uct8086", "mcp-servers.json");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    // ---- CRUD ----

    public List<McpServerConfig> listAll() {
        return configs.values().stream()
                .sorted(Comparator.comparing(McpServerConfig::name))
                .toList();
    }

    public Optional<McpServerConfig> get(String id) {
        return Optional.ofNullable(configs.get(id));
    }

    public McpServerConfig save(McpServerConfig config) {
        configs.put(config.id(), config);
        persist();
        log.info("Saved MCP server config: {} ({})", config.name(), config.id());
        return config;
    }

    public boolean delete(String id) {
        McpServerConfig removed = configs.remove(id);
        if (removed != null) {
            persist();
            log.info("Deleted MCP server config: {} ({})", removed.name(), removed.id());
            return true;
        }
        return false;
    }

    // ---- Persistence ----

    private void persist() {
        try {
            Path parent = configFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            mapper.writeValue(configFile.toFile(), new ArrayList<>(configs.values()));
        } catch (IOException e) {
            log.error("Failed to persist MCP configs to {}", configFile, e);
        }
    }

    private void load() {
        if (!Files.exists(configFile)) {
            log.info("MCP config file does not exist, creating: {}", configFile);
            persist();
            return;
        }
        try {
            List<McpServerConfig> list = mapper.readValue(
                    configFile.toFile(),
                    new TypeReference<List<McpServerConfig>>() {});
            list.forEach(c -> configs.put(c.id(), c));
            log.info("Loaded {} MCP server configs from {}", configs.size(), configFile);
        } catch (IOException e) {
            log.error("Failed to load MCP configs from {}", configFile, e);
        }
    }
}
