package uct8086.ai.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import uct8086.ai.common.model.McpServerConfig;
import uct8086.ai.core.config.HarnessProperties;

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
    private final HarnessProperties properties;

    // Dedicated pool for MCP tool calls so a slow MCP server never blocks the agent
    // loop. The actual timeout is enforced via Future.get(timeout).
    private final ExecutorService toolCallExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "uct8086-mcp-tool-call");
        t.setDaemon(true);
        return t;
    });

    public McpClientService(McpConfigManager configManager,
                            McpConnectionManager connectionManager,
                            HarnessProperties properties) {
        this.configManager = configManager;
        this.connectionManager = connectionManager;
        this.properties = properties;
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
     *
     * <p>The call is dispatched to a dedicated thread pool and bounded by a timeout, so
     * a slow or unresponsive MCP server fails the call instead of blocking the agent
     * loop indefinitely.
     */
    public String callTool(Long userId, String toolName, Map<String, Object> arguments) {
        List<ToolCallback> liveTools = connectionManager.getToolCallbacks(userId);
        for (ToolCallback cb : liveTools) {
            if (cb.getToolDefinition().name().equals(toolName)) {
                String input;
                try {
                    input = arguments != null && !arguments.isEmpty()
                            ? OBJECT_MAPPER.writeValueAsString(arguments)
                            : "{}";
                } catch (Exception e) {
                    log.error("[MCP-CALL] userId={} tool={} 参数序列化失败: {}", userId, toolName, e.getMessage());
                    return "Error: failed to serialize arguments: " + e.getMessage();
                }

                long timeoutSeconds = Math.max(1, properties.getMcpRequestTimeoutSeconds());
                long start = System.currentTimeMillis();
                log.info("[MCP-CALL] userId={} tool={} 开始调用, 参数={}, 超时={}s",
                        userId, toolName, truncate(input, 200), timeoutSeconds);
                Callable<String> task = () -> cb.call(input);
                Future<String> future = toolCallExecutor.submit(task);
                try {
                    String result = future.get(timeoutSeconds, TimeUnit.SECONDS);
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("[MCP-CALL] userId={} tool={} 调用成功, 耗时={}ms, 结果长度={}",
                            userId, toolName, elapsed, result != null ? result.length() : 0);
                    return result;
                } catch (TimeoutException e) {
                    future.cancel(true);
                    long elapsed = System.currentTimeMillis() - start;
                    log.error("[MCP-CALL] userId={} tool={} 调用超时, 超过{}s(实际等待{}ms)",
                            userId, toolName, timeoutSeconds, elapsed);
                    return "Error: MCP tool call timed out after " + timeoutSeconds + "s: " + toolName;
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    log.error("[MCP-CALL] userId={} tool={} 调用失败, 耗时={}ms: {}",
                            userId, toolName, elapsed, e.getMessage(), e);
                    return "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            }
        }
        log.warn("[MCP-CALL] userId={} tool={} 未找到对应的 MCP 工具 (可用工具数={})",
                userId, toolName, liveTools.size());
        return "MCP tool not found: " + toolName;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
