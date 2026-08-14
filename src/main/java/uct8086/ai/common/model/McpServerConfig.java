package uct8086.ai.common.model;

import java.util.List;

/**
 * MCP (Model Context Protocol) server configuration.
 *
 * <p>Supports the Streamable HTTP transport (remote HTTP endpoint via url).
 * The deprecated HTTP+SSE ("sse") type is also accepted for backward compatibility
 * and routed through the Streamable HTTP transport.
 *
 * <p>Configs are persisted per user under {@code .uct8086/mcp-servers/{userId}.json}.
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
            type = "streamable-http";
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
