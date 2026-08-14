package uct8086.ai.mcp;

import uct8086.ai.client.McpClient;
import uct8086.ai.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import uct8086.ai.common.model.McpServerConfig;
import uct8086.ai.core.config.HarnessProperties;

import java.time.Duration;

/**
 * Manages MCP client connections at runtime, scoped per user.
 *
 * <p>Each user has their own set of MCP connections built from their persisted
 * configs. Connections are created lazily (on first access / explicit refresh for
 * that user) rather than eagerly at startup, because users are runtime-authenticated.
 */
@Component
public class McpConnectionManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);

    private final McpConfigManager configManager;
    private final HarnessProperties properties;

    // userId -> active MCP clients
    private final Map<Long, List<McpSyncClient>> clientsByUser = new ConcurrentHashMap<>();
    // userId -> (serverId -> error message)
    private final Map<Long, Map<String, String>> connectionErrorsByUser = new ConcurrentHashMap<>();
    // userId -> cached tool callbacks
    private final Map<Long, List<ToolCallback>> toolCallbacksByUser = new ConcurrentHashMap<>();

    public McpConnectionManager(McpConfigManager configManager, HarnessProperties properties) {
        this.configManager = configManager;
        this.properties = properties;
    }

    /**
     * Close existing connections for a user and re-create from their persisted configs.
     */
    public synchronized void refresh(Long userId) {
        closeAll(userId);

        List<McpSyncClient> newClients = new ArrayList<>();
        Map<String, String> errors = new ConcurrentHashMap<>();

        for (McpServerConfig config : configManager.listAll(userId)) {
            if (!config.enabled()) {
                log.info("MCP server disabled, skipping (user {}): {}", userId, config.name());
                continue;
            }
            try {
                McpSyncClient client = createClient(config);
                client.initialize();
                newClients.add(client);
                errors.remove(config.id());
                log.info("MCP connected (user {}): {} ({} tools)", userId, config.name(),
                        client.listTools() != null ? client.listTools().tools().size() : 0);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                errors.put(config.id(), msg);
                log.error("MCP connection failed (user {}): {} — {}", userId, config.name(), msg);
            }
        }

        clientsByUser.put(userId, newClients);
        connectionErrorsByUser.put(userId, errors);

        List<ToolCallback> callbacks = new ArrayList<>();
        for (McpSyncClient client : newClients) {
            SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                    .mcpClients(List.of(client))
                    .build();
            callbacks.addAll(Arrays.asList(provider.getToolCallbacks()));
        }
        toolCallbacksByUser.put(userId, callbacks);

        log.info("MCP connection manager (user {}): {} active / {} configured",
                userId, newClients.size(), configManager.listAll(userId).size());
    }

    /**
     * All MCP tool callbacks currently available for the given user.
     *
     * <p>Lazy auto-connect: if no connection has been established yet for this user
     * but they have enabled MCP configs, trigger a refresh so MCP tools become
     * available on first use (e.g. first agent turn) without a manual "reconnect".
     */
    public List<ToolCallback> getToolCallbacks(Long userId) {
        List<ToolCallback> cached = toolCallbacksByUser.get(userId);
        if (cached != null) {
            return cached;
        }
        // Nothing cached yet — auto-connect if the user has enabled configs.
        boolean hasEnabledConfig = configManager.listAll(userId).stream().anyMatch(McpServerConfig::enabled);
        if (hasEnabledConfig) {
            log.info("MCP tools not loaded yet for user {}, auto-connecting...", userId);
            refresh(userId);
            List<ToolCallback> loaded = toolCallbacksByUser.get(userId);
            return loaded != null ? loaded : List.of();
        }
        return List.of();
    }

    /** Connection-level errors keyed by server id (for UI feedback). */
    public Map<String, String> getConnectionErrors(Long userId) {
        Map<String, String> errors = connectionErrorsByUser.get(userId);
        return errors != null ? Map.copyOf(errors) : Map.of();
    }

    /** Number of currently active MCP connections for the given user. */
    public int getActiveCount(Long userId) {
        List<McpSyncClient> clients = clientsByUser.get(userId);
        return clients != null ? clients.size() : 0;
    }

    // ---- internals ----

    private McpSyncClient createClient(McpServerConfig config) {
        // Only Streamable HTTP is supported. The "sse" type (deprecated HTTP+SSE
        // transport) is also routed through Streamable HTTP for backward compatibility.
        return createStreamableHttpClient(config);
    }

    private McpSyncClient createStreamableHttpClient(McpServerConfig config) {
        String url = config.url();
        URI uri = URI.create(url);
        String baseUri = uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String endpoint = uri.getPath() + (uri.getQuery() != null ? "?" + uri.getQuery() : "");
        if (endpoint.isEmpty()) {
            endpoint = "/mcp";
        }

        var transport = HttpClientStreamableHttpTransport.builder(baseUri)
                .endpoint(endpoint)
                .build();

        // Set an explicit request timeout so a slow/unresponsive MCP server fails the
        // tool call after N seconds instead of blocking the agent loop indefinitely.
        Duration requestTimeout = Duration.ofSeconds(Math.max(1, properties.getMcpRequestTimeoutSeconds()));
        return McpClient.sync(transport)
                .requestTimeout(requestTimeout)
                .build();
    }

    private void closeAll(Long userId) {
        List<McpSyncClient> existing = clientsByUser.get(userId);
        if (existing != null) {
            for (McpSyncClient client : existing) {
                try {
                    client.close();
                } catch (Exception ignored) {
                    // best-effort close
                }
            }
        }
        clientsByUser.remove(userId);
        toolCallbacksByUser.remove(userId);
        connectionErrorsByUser.remove(userId);
    }

    @Override
    public void destroy() {
        for (Long userId : clientsByUser.keySet()) {
            closeAll(userId);
        }
    }
}
