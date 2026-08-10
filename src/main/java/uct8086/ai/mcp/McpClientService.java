package uct8086.ai.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import uct8086.ai.common.model.McpServerConfig;

/**
 * MCP (Model Context Protocol) client service.
 *
 * <p>Spring AI 2.0 auto-creates {@link ToolCallback} beans for each configured
 * MCP server. This service queries those beans to list servers, tools, and
 * invoke them programmatically.
 *
 * <p>Configuration in {@code application.yml}:
 * <pre>{@code
 * spring.ai.mcp.client.stdio.connections:
 *   filesystem:
 *     command: npx
 *     args: ["-y", "@modelcontextprotocol/server-filesystem", "/allowed/dir"]
 * spring.ai.mcp.client.sse.connections:
 *   remote-api:
 *     url: http://remote-server:8080/sse
 * }</pre>
 */
@Component
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

    private final McpConfigManager configManager;
    private final McpConnectionManager connectionManager;

    public McpClientService(McpConfigManager configManager,
                            McpConnectionManager connectionManager) {
        this.configManager = configManager;
        this.connectionManager = connectionManager;
    }

    /**
     * List all MCP servers with their configuration and runtime status.
     * Uses {@link McpConnectionManager} to report actual connection state.
     */
    public List<Map<String, Object>> listServers() {
        List<McpServerConfig> configs = configManager.listAll();
        Map<String, String> errors = connectionManager.getConnectionErrors();
        List<ToolCallback> liveTools = connectionManager.getToolCallbacks();

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

    public List<Map<String, String>> listTools() {
        List<ToolCallback> liveTools = connectionManager.getToolCallbacks();
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
     * Call an MCP tool by name.
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        List<ToolCallback> liveTools = connectionManager.getToolCallbacks();
        for (ToolCallback cb : liveTools) {
            if (cb.getToolDefinition().name().equals(toolName)) {
                try {
                    String input = arguments != null && !arguments.isEmpty()
                            ? new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arguments)
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
