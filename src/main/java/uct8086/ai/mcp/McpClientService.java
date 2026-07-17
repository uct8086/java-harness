package uct8086.ai.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

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

    private final ApplicationContext applicationContext;

    public McpClientService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * List all available MCP tools (ToolCallback beans created by Spring AI).
     */
    public List<Map<String, String>> listTools() {
        Map<String, ToolCallback> beans = applicationContext.getBeansOfType(ToolCallback.class);
        return beans.values().stream()
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
        Map<String, ToolCallback> beans = applicationContext.getBeansOfType(ToolCallback.class);
        for (ToolCallback cb : beans.values()) {
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
