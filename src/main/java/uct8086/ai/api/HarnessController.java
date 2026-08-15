package uct8086.ai.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uct8086.ai.auth.service.CurrentUser;
import uct8086.ai.common.enums.PermissionMode;
import uct8086.ai.common.model.AgentMessage;
import uct8086.ai.common.model.ToolDescriptor;
import uct8086.ai.core.cost.CostTracker;
import uct8086.ai.core.engine.AgentEngine;
import uct8086.ai.core.engine.AgentLoopResult;
import uct8086.ai.core.permission.PermissionChecker;
import uct8086.ai.core.session.SessionManager;
import uct8086.ai.core.tool.ToolRegistry;
import uct8086.ai.skills.Skill;
import uct8086.ai.skills.SkillRegistry;
import uct8086.ai.mcp.McpClientService;
import uct8086.ai.mcp.McpConfigManager;
import uct8086.ai.mcp.McpConnectionManager;
import uct8086.ai.common.model.McpServerConfig;
import uct8086.ai.memory.MemoryEntry;
import uct8086.ai.memory.MemoryStore;
import uct8086.ai.memory.MemoryConsolidationService;
import uct8086.ai.tasks.BackgroundTask;
import uct8086.ai.tasks.TaskManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;

/**
 * REST API controller for the UCT8086-AI harness.
 * Exposes the agent engine and all subsystems via HTTP endpoints.
 */
@RestController
@RequestMapping("/api")
public class HarnessController {

    private static final Logger log = LoggerFactory.getLogger(HarnessController.class);

    private final AgentEngine agentEngine;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final CostTracker costTracker;
    private final PermissionChecker permissionChecker;
    private final SkillRegistry skillRegistry;
    private final MemoryStore memoryStore;
    private final MemoryConsolidationService memoryConsolidationService;
    private final TaskManager taskManager;
    private final McpClientService mcpClientService;
    private final McpConfigManager mcpConfigManager;
    private final McpConnectionManager mcpConnectionManager;

    /** RAG vector store (optional — disabled when pgvector is unavailable). */
    @Autowired(required = false)
    @Qualifier("pgVectorStore")
    private VectorStore vectorStore;

    public HarnessController(AgentEngine agentEngine,
                             ToolRegistry toolRegistry,
                             SessionManager sessionManager,
                             CostTracker costTracker,
                             PermissionChecker permissionChecker,
                             SkillRegistry skillRegistry,
                             MemoryStore memoryStore,
                             MemoryConsolidationService memoryConsolidationService,
                             TaskManager taskManager,
                             McpClientService mcpClientService,
                             McpConfigManager mcpConfigManager,
                             McpConnectionManager mcpConnectionManager) {
        this.agentEngine = agentEngine;
        this.toolRegistry = toolRegistry;
        this.sessionManager = sessionManager;
        this.costTracker = costTracker;
        this.permissionChecker = permissionChecker;
        this.skillRegistry = skillRegistry;
        this.memoryStore = memoryStore;
        this.memoryConsolidationService = memoryConsolidationService;
        this.taskManager = taskManager;
        this.mcpClientService = mcpClientService;
        this.mcpConfigManager = mcpConfigManager;
        this.mcpConnectionManager = mcpConnectionManager;
    }

    // ========== Agent Engine ==========

    /**
     * Send a prompt to the agent.
     */
    @PostMapping("/chat")
    public AgentLoopResult chat(@RequestBody ChatRequest request) {
        Long userId = CurrentUser.requireId();
        log.info("Chat request received - user: {}, prompt: '{}', sessionId: {}",
                userId,
                request.prompt() != null && request.prompt().length() > 100
                        ? request.prompt().substring(0, 100) + "..."
                        : request.prompt(),
                request.sessionId());
        return agentEngine.execute(userId, request.prompt(), request.sessionId());
    }

    /**
     * Send a prompt with additional context (skills, memory).
     */
    @PostMapping("/chat-with-context")
    public AgentLoopResult chatWithContext(@RequestBody ChatWithContextRequest request) {
        Long userId = CurrentUser.requireId();
        return agentEngine.execute(userId, request.prompt(), request.sessionId(), request.additionalContext());
    }

    /**
     * Streaming chat via SSE. Returns events as the agent loop progresses:
     * {@code response} (final text), {@code done} (usage stats), or {@code error}.
     */
    @PostMapping(value = "/chat/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        Long userId = CurrentUser.id();
        if (userId == null) {
            // Auth is permitAll for this endpoint (to avoid async dispatch issues),
            // but we still require a valid cookie. Return an immediately-completed
            // emitter that signals "unauthorized".
            SseEmitter unauthorized = new SseEmitter();
            try {
                unauthorized.send(SseEmitter.event().name("error").data("未登录"));
                unauthorized.complete();
            } catch (Exception ignored) {
                unauthorized.completeWithError(new RuntimeException("unauthorized"));
            }
            return unauthorized;
        }
        // Timeout: 5 minutes (agent loop can be long with multiple turns)
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        agentEngine.executeStream(userId, request.prompt(), request.sessionId(), emitter);
        return emitter;
    }

    // ========== Sessions ==========

    @GetMapping("/sessions")
    public List<uct8086.ai.common.model.SessionInfo> listSessions(
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "20") int limit) {
        return sessionManager.listSessions(CurrentUser.requireId(), offset, limit);
    }

    @PostMapping("/sessions")
    public SessionManager.ConversationSession createSession(@RequestParam(required = false) String name) {
        return sessionManager.createSession(CurrentUser.requireId(), name);
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, Boolean> deleteSession(@PathVariable String id) {
        return Map.of("deleted", sessionManager.deleteSession(CurrentUser.requireId(), id));
    }

    /**
     * Get the persisted message history of a session (user / assistant / tool).
     */
    @GetMapping("/sessions/{id}/messages")
    public List<AgentMessage> getSessionMessages(@PathVariable String id) {
        return sessionManager.getMessages(CurrentUser.requireId(), id);
    }

    // ========== Tools ==========

    @GetMapping("/tools")
    public List<ToolDescriptor> listTools() {
        return toolRegistry.listTools();
    }

    // ========== Permission ==========

    @GetMapping("/permission/mode")
    public Map<String, PermissionMode> getPermissionMode() {
        return Map.of("mode", permissionChecker.getMode());
    }

    @PutMapping("/permission/mode")
    public Map<String, PermissionMode> setPermissionMode(@RequestParam PermissionMode mode) {
        permissionChecker.setMode(mode);
        return Map.of("mode", permissionChecker.getMode());
    }

    // ========== Cost Tracking ==========

    @GetMapping("/cost/total")
    public uct8086.ai.common.model.TokenUsage getTotalCost() {
        return costTracker.getTotalUsage(CurrentUser.requireId());
    }

    @GetMapping("/cost/session/{sessionId}")
    public uct8086.ai.common.model.TokenUsage getSessionCost(@PathVariable String sessionId) {
        return costTracker.getSessionUsage(CurrentUser.requireId(), sessionId);
    }

    // ========== Skills ==========

    @GetMapping("/skills")
    public List<Skill> listSkills() {
        return skillRegistry.listSkills(CurrentUser.requireId());
    }

    @PostMapping("/skills")
    public Skill addSkill(@RequestBody AddSkillRequest request) {
        Skill skill = new Skill(request.name(), request.description(), request.content(), null);
        skillRegistry.register(CurrentUser.requireId(), skill);
        return skill;
    }

    @GetMapping("/skills/{name}")
    public Skill getSkill(@PathVariable String name) {
        return skillRegistry.getSkill(CurrentUser.requireId(), name).orElse(null);
    }

    // ========== Memory ==========

    @GetMapping("/memory")
    public List<MemoryEntry> listMemory() {
        return memoryStore.getAll(CurrentUser.requireId());
    }

    @PostMapping("/memory")
    public MemoryEntry addMemory(@RequestBody AddMemoryRequest request) {
        return memoryStore.save(CurrentUser.requireId(), new MemoryEntry(request.category(), request.content()));
    }

    @GetMapping("/memory/search")
    public List<MemoryEntry> searchMemory(@RequestParam String keyword) {
        return memoryStore.search(CurrentUser.requireId(), keyword);
    }

    @PutMapping("/memory/{id}")
    public MemoryEntry updateMemory(@PathVariable String id, @RequestBody AddMemoryRequest request) {
        MemoryEntry existing = memoryStore.get(CurrentUser.requireId(), id)
                .orElseThrow(() -> new IllegalArgumentException("Memory not found: " + id));
        MemoryEntry updated = new MemoryEntry(id, request.category(), request.content(),
                existing.createdAt(), java.time.Instant.now());
        return memoryStore.update(CurrentUser.requireId(), updated);
    }

    @DeleteMapping("/memory/{id}")
    public Map<String, Boolean> deleteMemory(@PathVariable String id) {
        return Map.of("deleted", memoryStore.delete(CurrentUser.requireId(), id));
    }

    /** Manually trigger memory consolidation for the current user. */
    @PostMapping("/memory/consolidate")
    public Map<String, Integer> consolidateMemory() {
        return Map.of("saved", memoryConsolidationService.consolidateNow(CurrentUser.requireId()));
    }

    // ========== Tasks ==========

    @GetMapping("/tasks")
    public List<BackgroundTask> listTasks() {
        return taskManager.listTasks(CurrentUser.requireId());
    }

    @GetMapping("/tasks/{id}")
    public BackgroundTask getTask(@PathVariable String id) {
        return taskManager.getTask(CurrentUser.requireId(), id).orElse(null);
    }

    @DeleteMapping("/tasks/{id}")
    public Map<String, Boolean> cancelTask(@PathVariable String id) {
        return Map.of("cancelled", taskManager.cancelTask(CurrentUser.requireId(), id));
    }

    // ========== Knowledge (Vector Store / pgvector) ==========

    @PostMapping("/knowledge/ingest")
    public Map<String, Object> ingestKnowledge(@RequestBody IngestRequest request) {
        if (vectorStore == null) {
            return Map.of("success", false, "error", "VectorStore not configured (pgvector unavailable)");
        }
        try {
            Document doc = new Document(request.content(),
                    request.metadata() != null ? request.metadata() : Map.of());
            vectorStore.add(List.of(doc));
            log.info("Ingested document to knowledge base ({} chars)", request.content().length());
            return Map.of("success", true);
        } catch (Exception e) {
            log.error("Failed to ingest document", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @GetMapping("/knowledge/search")
    public List<SearchResult> searchKnowledge(@RequestParam String q,
                                              @RequestParam(defaultValue = "5") int topK) {
        if (vectorStore == null) return List.of();
        return vectorStore.similaritySearch(
                        SearchRequest.builder().query(q).topK(topK).build())
                .stream()
                .map(d -> new SearchResult(d.getText(), d.getMetadata()))
                .toList();
    }

    public record SearchResult(String content, Map<String, Object> metadata) {}

    public record IngestRequest(String content, Map<String, Object> metadata) {}

    // ========== MCP (Model Context Protocol) ==========

    /**
     * List all MCP servers with their config and runtime status.
     */
    @GetMapping("/mcp/servers")
    public List<Map<String, Object>> listMcpServers() {
        return mcpClientService.listServers(CurrentUser.requireId());
    }

    /**
     * List MCP tools currently active (from Spring AI ToolCallbacks).
     */
    @GetMapping("/mcp/tools")
    public List<Map<String, String>> listMcpTools() {
        return mcpClientService.listTools(CurrentUser.requireId());
    }

    /**
     * Add a new MCP server configuration.
     */
    @PostMapping("/mcp/servers")
    public McpServerConfig addMcpServer(@RequestBody AddMcpServerRequest request) {
        Long userId = CurrentUser.requireId();
        String id = UUID.randomUUID().toString().substring(0, 8);
        McpServerConfig config = new McpServerConfig(
                id,
                request.name(),
                request.type() != null ? request.type() : "streamable-http",
                request.command(),
                request.args() != null ? request.args() : List.of(),
                request.url(),
                true
        );
        McpServerConfig saved = mcpConfigManager.save(userId, config);
        // Auto-connect so the new server's tools are available immediately.
        mcpConnectionManager.refresh(userId);
        return saved;
    }

    /**
     * Update an MCP server configuration.
     */
    @PutMapping("/mcp/servers/{id}")
    public McpServerConfig updateMcpServer(@PathVariable String id,
                                            @RequestBody AddMcpServerRequest request) {
        Long userId = CurrentUser.requireId();
        return mcpConfigManager.get(userId, id)
                .map(existing -> {
                    McpServerConfig updated = new McpServerConfig(
                            id,
                            request.name() != null ? request.name() : existing.name(),
                            request.type() != null ? request.type() : existing.type(),
                            request.command() != null ? request.command() : existing.command(),
                            request.args() != null ? request.args() : existing.args(),
                            request.url() != null ? request.url() : existing.url(),
                            existing.enabled()
                    );
                    McpServerConfig saved = mcpConfigManager.save(userId, updated);
                    mcpConnectionManager.refresh(userId);
                    return saved;
                })
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + id));
    }

    /**
     * Toggle a server enabled/disabled.
     */
    @PutMapping("/mcp/servers/{id}/toggle")
    public McpServerConfig toggleMcpServer(@PathVariable String id) {
        Long userId = CurrentUser.requireId();
        return mcpConfigManager.get(userId, id)
                .map(c -> {
                    McpServerConfig saved = mcpConfigManager.save(userId, c.withEnabled(!c.enabled()));
                    mcpConnectionManager.refresh(userId);
                    return saved;
                })
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + id));
    }

    /**
     * Delete an MCP server configuration.
     */
    @DeleteMapping("/mcp/servers/{id}")
    public Map<String, Boolean> deleteMcpServer(@PathVariable String id) {
        Long userId = CurrentUser.requireId();
        boolean deleted = mcpConfigManager.delete(userId, id);
        if (deleted) {
            mcpConnectionManager.refresh(userId);
        }
        return Map.of("deleted", deleted);
    }

    /**
     * Reconnect to all enabled MCP servers for the current user.
     * Call after adding/removing/updating configs to apply changes.
     */
    @PostMapping("/mcp/refresh")
    public Map<String, Object> refreshMcpConnections() {
        Long userId = CurrentUser.requireId();
        mcpConnectionManager.refresh(userId);
        return Map.of("active", mcpConnectionManager.getActiveCount(userId),
                "errors", mcpConnectionManager.getConnectionErrors(userId));
    }

    // ========== Request DTOs ==========

    public record ChatRequest(String prompt, String sessionId) {}

    public record ChatWithContextRequest(String prompt, String sessionId, String additionalContext) {}

    public record AddMemoryRequest(String category, String content) {}

    public record AddSkillRequest(String name, String description, String content) {}

    public record AddMcpServerRequest(String name, String type, String command,
                                       List<String> args, String url) {}
}
