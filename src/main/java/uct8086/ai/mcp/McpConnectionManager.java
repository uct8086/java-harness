package uct8086.ai.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import uct8086.ai.common.model.McpServerConfig;

/**
 * Manages MCP client connections at runtime.
 *
 * <p>Reads persisted configs from {@link McpConfigManager} and creates
 * real {@link McpSyncClient} instances. Wraps discovered tools via
 * {@link SyncMcpToolCallbackProvider} so they are available to the
 * {@link uct8086.ai.core.engine.AgentEngine}.
 *
 * <p>Connections are refreshed on startup and can be re-triggered via
 * {@link #refresh()} (e.g. from the web UI after adding a new server).
 */
@Component
public class McpConnectionManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);

    private final McpConfigManager configManager;
    private final List<McpSyncClient> clients = new CopyOnWriteArrayList<>();
    private final Map<String, String> connectionErrors = new ConcurrentHashMap<>();
    private volatile SyncMcpToolCallbackProvider provider;
    private volatile List<ToolCallback> cachedToolCallbacks = List.of();

    public McpConnectionManager(McpConfigManager configManager) {
        this.configManager = configManager;
    }

    /** Connect to all enabled MCP servers on startup. */
    @PostConstruct
    public void initialize() {
        refresh();
    }

    /**
     * Close existing connections and re-create from persisted configs.
     * Called on startup and whenever the user adds/removes a server.
     */
    public synchronized void refresh() {
        closeAll();

        List<McpSyncClient> newClients = new ArrayList<>();
        connectionErrors.clear();

        for (McpServerConfig config : configManager.listAll()) {
            if (!config.enabled()) {
                log.info("MCP server disabled, skipping: {}", config.name());
                continue;
            }
            try {
                McpSyncClient client = createClient(config);
                client.initialize();
                newClients.add(client);
                connectionErrors.remove(config.id());
                log.info("MCP connected: {} ({} tools)", config.name(),
                        client.listTools() != null ? client.listTools().tools().size() : 0);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                connectionErrors.put(config.id(), msg);
                log.error("MCP connection failed: {} — {}", config.name(), msg);
            }
        }

        clients.clear();
        clients.addAll(newClients);

        this.provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(this.clients)
                .build();

        this.cachedToolCallbacks = Arrays.asList(this.provider.getToolCallbacks());

        log.info("MCP connection manager: {} active / {} configured",
                clients.size(), configManager.listAll().size());
    }

    /** All MCP tool callbacks currently available. */
    public List<ToolCallback> getToolCallbacks() {
        return cachedToolCallbacks;
    }

    /** Connection-level errors keyed by server id (for UI feedback). */
    public Map<String, String> getConnectionErrors() {
        return Map.copyOf(connectionErrors);
    }

    /** Number of currently active MCP connections. */
    public int getActiveCount() {
        return clients.size();
    }

    // ---- internals ----

    private McpSyncClient createClient(McpServerConfig config) {
        if ("sse".equalsIgnoreCase(config.type())) {
            return createSseClient(config);
        } else if ("streamable-http".equalsIgnoreCase(config.type())) {
            return createStreamableHttpClient(config);
        } else {
            return createStdioClient(config);
        }
    }

    private McpSyncClient createSseClient(McpServerConfig config) {
        String url = config.url();
        URI uri = URI.create(url);
        String baseUri = uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String sseEndpoint = uri.getPath() + (uri.getQuery() != null ? "?" + uri.getQuery() : "");

        var transport = HttpClientSseClientTransport.builder(baseUri)
                .sseEndpoint(sseEndpoint)
                .build();

        return McpClient.sync(transport).build();
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

        return McpClient.sync(transport).build();
    }

    private McpSyncClient createStdioClient(McpServerConfig config) {
        List<String> args = config.args() != null ? config.args() : List.of();
        var params = ServerParameters.builder(config.command())
                .args(args.toArray(new String[0]))
                .build();

        // StdioClientTransport needs a JSON mapper; use McpJsonDefaults
        var transport = new StdioClientTransport(params,
                io.modelcontextprotocol.json.McpJsonDefaults.getMapper());

        return McpClient.sync(transport).build();
    }

    private void closeAll() {
        for (McpSyncClient client : clients) {
            try {
                client.close();
            } catch (Exception ignored) {
                // best-effort close
            }
        }
        clients.clear();
        cachedToolCallbacks = List.of();
    }

    @Override
    public void destroy() {
        closeAll();
    }
}
