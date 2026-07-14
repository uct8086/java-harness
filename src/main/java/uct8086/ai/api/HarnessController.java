package uct8086.ai.api;

import org.springframework.beans.factory.annotation.Autowired;
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
import uct8086.ai.memory.FileMemoryStore;
import uct8086.ai.memory.MemoryEntry;
import uct8086.ai.tasks.BackgroundTask;
import uct8086.ai.tasks.TaskManager;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;
import uct8086.ai.vectorstore.PgVectorEmbeddingStore;

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
    private final FileMemoryStore memoryStore;
    private final TaskManager taskManager;

    /** RAG vector store (optional — disabled when pgvector is unavailable). */
    @Autowired(required = false)
    private PgVectorEmbeddingStore vectorStore;

    public HarnessController(AgentEngine agentEngine,
                             ToolRegistry toolRegistry,
                             SessionManager sessionManager,
                             CostTracker costTracker,
                             PermissionChecker permissionChecker,
                             SkillRegistry skillRegistry,
                             FileMemoryStore memoryStore,
                             TaskManager taskManager) {
        this.agentEngine = agentEngine;
        this.toolRegistry = toolRegistry;
        this.sessionManager = sessionManager;
        this.costTracker = costTracker;
        this.permissionChecker = permissionChecker;
        this.skillRegistry = skillRegistry;
        this.memoryStore = memoryStore;
        this.taskManager = taskManager;
    }

    // ========== Agent Engine ==========

    /**
     * Send a prompt to the agent.
     */
    @PostMapping("/chat")
    public AgentLoopResult chat(@RequestBody ChatRequest request) {
        log.info("Chat request received - prompt: '{}', sessionId: {}",
                request.prompt() != null && request.prompt().length() > 100
                        ? request.prompt().substring(0, 100) + "..."
                        : request.prompt(),
                request.sessionId());
        return agentEngine.execute(request.prompt(), request.sessionId());
    }

    /**
     * Send a prompt with additional context (skills, memory).
     */
    @PostMapping("/chat-with-context")
    public AgentLoopResult chatWithContext(@RequestBody ChatWithContextRequest request) {
        return agentEngine.execute(request.prompt(), request.sessionId(), request.additionalContext());
    }

    // ========== Sessions ==========

    @GetMapping("/sessions")
    public List<uct8086.ai.common.model.SessionInfo> listSessions() {
        return sessionManager.listSessions();
    }

    @PostMapping("/sessions")
    public SessionManager.ConversationSession createSession(@RequestParam(required = false) String name) {
        return sessionManager.createSession(name);
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, Boolean> deleteSession(@PathVariable String id) {
        return Map.of("deleted", sessionManager.deleteSession(id));
    }

    /**
     * Get the persisted message history of a session (user / assistant / tool).
     */
    @GetMapping("/sessions/{id}/messages")
    public List<AgentMessage> getSessionMessages(@PathVariable String id) {
        return sessionManager.getMessages(id);
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
        return costTracker.getTotalUsage();
    }

    @GetMapping("/cost/session/{sessionId}")
    public uct8086.ai.common.model.TokenUsage getSessionCost(@PathVariable String sessionId) {
        return costTracker.getSessionUsage(sessionId);
    }

    // ========== Skills ==========

    @GetMapping("/skills")
    public List<Skill> listSkills() {
        return skillRegistry.listSkills();
    }

    @PostMapping("/skills")
    public Skill addSkill(@RequestBody AddSkillRequest request) {
        Skill skill = new Skill(request.name(), request.description(), request.content(), null);
        skillRegistry.register(skill);
        return skill;
    }

    @GetMapping("/skills/{name}")
    public Skill getSkill(@PathVariable String name) {
        return skillRegistry.getSkill(name).orElse(null);
    }

    // ========== Memory ==========

    @GetMapping("/memory")
    public List<MemoryEntry> listMemory() {
        return memoryStore.getAll();
    }

    @PostMapping("/memory")
    public MemoryEntry addMemory(@RequestBody AddMemoryRequest request) {
        return memoryStore.save(new MemoryEntry(request.category(), request.content()));
    }

    @GetMapping("/memory/search")
    public List<MemoryEntry> searchMemory(@RequestParam String keyword) {
        return memoryStore.search(keyword);
    }

    // ========== Tasks ==========

    @GetMapping("/tasks")
    public List<BackgroundTask> listTasks() {
        return taskManager.listTasks();
    }

    @GetMapping("/tasks/{id}")
    public BackgroundTask getTask(@PathVariable String id) {
        return taskManager.getTask(id).orElse(null);
    }

    @DeleteMapping("/tasks/{id}")
    public Map<String, Boolean> cancelTask(@PathVariable String id) {
        return Map.of("cancelled", taskManager.cancelTask(id));
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
            vectorStore.add(doc);
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
        return vectorStore.search(q, topK).stream()
                .map(d -> new SearchResult(d.getText(), d.getMetadata()))
                .toList();
    }

    public record SearchResult(String content, Map<String, Object> metadata) {}

    public record IngestRequest(String content, Map<String, Object> metadata) {}

    // ========== Request DTOs ==========

    public record ChatRequest(String prompt, String sessionId) {}

    public record ChatWithContextRequest(String prompt, String sessionId, String additionalContext) {}

    public record AddMemoryRequest(String category, String content) {}

    public record AddSkillRequest(String name, String description, String content) {}
}
