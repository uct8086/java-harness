package uct8086.ai.common.model;

import java.util.List;

/**
 * MCP (Model Context Protocol) server configuration.
 *
 * <p>Supports two transport types:
 * <ul>
 *   <li><b>stdio</b> — local process (command + args)</li>
 *   <li><b>sse</b> — remote HTTP SSE endpoint (url)</li>
 * </ul>
 *
 * <p>Configs are persisted to {@code .uct8086/mcp-servers.json} and
 * become effective on next application restart (Spring AI 2.0
 * auto-creates {@link org.springframework.ai.tool.ToolCallback} beans
 * from {@code application.yml} at startup).
 */
public record McpServerConfig(
        String id,
        String name,
        String type,
        String command,
        List<String> args,
        String url,
        boolean enabled
) {

    /** Compact constructor with defaults. */
    public McpServerConfig {
        if (type == null || type.isBlank()) {
            type = "stdio";
        }
    }

    public static McpServerConfig create(String id, String name, String type,
                                          String command, List<String> args,
                                          String url, boolean enabled) {
        return new McpServerConfig(id, name, type, command, args, url, enabled);
    }

    public McpServerConfig withEnabled(boolean enabled) {
        return new McpServerConfig(id, name, type, command, args, url, enabled);
    }
}
