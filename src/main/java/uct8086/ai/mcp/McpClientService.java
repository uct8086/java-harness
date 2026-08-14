package uct8086.ai.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import uct8086.ai.common.model.McpServerConfig;

/**
 * MCP (Model Context Protocol) client service, scoped per user.
 *
 * <p>Each user's MCP servers, tools, and tool invocations are isolated via the
 * {@code userId} parameter passed through to {@link McpConfigManager} and
 * {@link McpConnectionManager}.
 */
@Component
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final McpConfigManager configManager;
    private final McpConnectionManager connectionManager;

    public McpClientService(McpConfigManager configManager,
                            McpConnectionManager connectionManager) {
        this.configManager = configManager;
        this.connectionManager = connectionManager;
    }

    /**
     * List all MCP servers for the given user with their configuration and runtime status.
     */
    public List<Map<String, Object>> listServers(Long userId) {
        List<McpServerConfig> configs = configManager.listAll(userId);
        Map<String, String> errors = connectionManager.getConnectionErrors(userId);
        List<ToolCallback> liveTools = connectionManager.getToolCallbacks(userId);

        return configs.stream()
                .map(c -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("id", c.id());
                    info.put("name", c.name());
                    info.put("type", c.type());
                    info.put("command", c.command());
                    info.put("args", c.args());
                    info.put("url", c.url());
                    info.put("enabled", c.enabled());

                    // Real connection status from McpConnectionManager
                    if (errors.containsKey(c.id())) {
                        info.put("status", "error");
                        info.put("error", errors.get(c.id()));
                    } else if (!liveTools.isEmpty()) {
                        // Check if any live tool belongs to this server (by name prefix)
                        boolean hasTools = liveTools.stream()
                                .anyMatch(t -> t.getToolDefinition().name()
                                        .toLowerCase().contains(c.name().toLowerCase()));
                        info.put("status", hasTools || errors.isEmpty() ? "connected" : "connected");
                    } else {
                        info.put("status", c.enabled() ? "connecting" : "disconnected");
                    }
                    return info;
                })
                .toList();
    }

    public List<Map<String, String>> listTools(Long userId) {
        List<ToolCallback> liveTools = connectionManager.getToolCallbacks(userId);
        return liveTools.stream()
                .map(cb -> {
                    Map<String, String> info = new HashMap<>();
                    info.put("name", cb.getToolDefinition().name());
                    info.put("description", cb.getToolDefinition().description());
                    return info;
                })
                .toList();
    }

    /**
     * Call an MCP tool by name for the given user.
     */
    public String callTool(Long userId, String toolName, Map<String, Object> arguments) {
        List<ToolCallback> liveTools = connectionManager.getToolCallbacks(userId);
        for (ToolCallback cb : liveTools) {
            if (cb.getToolDefinition().name().equals(toolName)) {
                try {
                    String input = arguments != null && !arguments.isEmpty()
                            ? OBJECT_MAPPER.writeValueAsString(arguments)
                            : "{}";
                    return cb.call(input);
                } catch (Exception e) {
                    log.error("MCP tool call failed: {}", toolName, e);
                    return "Error: " + e.getMessage();
                }
            }
        }
        return "MCP tool not found: " + toolName;
    }
}
