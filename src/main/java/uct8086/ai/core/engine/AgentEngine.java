package uct8086.ai.core.engine;

import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import uct8086.ai.common.model.AgentMessage;
import uct8086.ai.common.model.TokenUsage;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.core.config.HarnessProperties;
import uct8086.ai.core.cost.CostTracker;
import uct8086.ai.core.permission.PermissionChecker;
import uct8086.ai.core.prompt.PromptAssembler;
import uct8086.ai.core.session.SessionManager;
import uct8086.ai.core.tool.ToolExecutionService;
import uct8086.ai.core.tool.ToolRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final PromptAssembler promptAssembler;
    private final SessionManager sessionManager;
    private final CostTracker costTracker;
    private final PermissionChecker permissionChecker;
    private final HarnessProperties properties;
    private final ApplicationContext applicationContext;

    @Autowired(required = false)
    @Qualifier("pgVectorStore")
    private VectorStore vectorStore;

    public AgentEngine(@Qualifier("openAiChatModel") ChatModel chatModel,
                       ToolRegistry toolRegistry,
                       ToolExecutionService toolExecutionService,
                       PromptAssembler promptAssembler,
                       SessionManager sessionManager,
                       CostTracker costTracker,
                       PermissionChecker permissionChecker,
                       HarnessProperties properties,
                       ApplicationContext applicationContext) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.promptAssembler = promptAssembler;
        this.sessionManager = sessionManager;
        this.costTracker = costTracker;
        this.permissionChecker = permissionChecker;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    private String enrichWithRag(String systemPrompt, String userPrompt) {
        if (vectorStore == null) return systemPrompt;
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(userPrompt).topK(3).build());
            if (docs.isEmpty()) return systemPrompt;
            String context = docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
            log.debug("RAG: enriched prompt with {} relevant documents", docs.size());
            return systemPrompt + "\n\n## Relevant Documents (from vector store)\n" + context;
        } catch (Exception e) {
            log.warn("RAG enrichment failed, continuing without vector context", e);
            return systemPrompt;
        }
    }

    public AgentLoopResult execute(String userPrompt, String sessionId) {
        return execute(userPrompt, sessionId, null);
    }

    public AgentLoopResult execute(String userPrompt, String sessionId, String model) {
        return execute(userPrompt, sessionId, null, model);
    }

    public AgentLoopResult execute(String userPrompt, String sessionId,
                                   String additionalContext, String model) {
        SessionManager.ConversationSession session = (sessionId != null)
                ? sessionManager.getSession(sessionId).orElseGet(() -> sessionManager.createSession())
                : sessionManager.createSession();

        permissionChecker.setMode(properties.getPermissionMode());
        Path workingDir = Path.of(properties.getWorkingDirectory());
        ToolExecutionContext context = new ToolExecutionContext(
                session.id(), workingDir, properties.getPermissionMode());

        String systemPrompt = (additionalContext != null && !additionalContext.isEmpty())
                ? promptAssembler.buildSystemPrompt(additionalContext)
                : properties.getSystemPrompt() != null
                        ? properties.getSystemPrompt()
                        : promptAssembler.buildSystemPrompt();
        systemPrompt = enrichWithRag(systemPrompt, userPrompt);
        sessionManager.addMessage(session.id(), AgentMessage.user(userPrompt));

        ToolCallback[] callbacks = buildToolCallbacks();
        log.info("Starting agent loop for session {} (tools: {}, model: {})",
                session.id(), callbacks.length,
                model != null ? model : "default");

        long startTime = System.currentTimeMillis();
        try {
            HarnessToolCallbackAdapter.setContext(context);
            ChatClient chatClient = ChatClient.create(chatModel);

            var prompt = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .tools(callbacks);
            if (model != null && !model.isEmpty()) {
                prompt = prompt.options(
                        OpenAiChatOptions.builder()
                                .model(model));
            }

            ChatResponse chatResponse = prompt.call().chatResponse();

            String response = chatResponse.getResult().getOutput().getText();
            TokenUsage usage = extractUsage(chatResponse);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Model responded in {}ms, in={} out={}", elapsed,
                    usage.inputTokens(), usage.outputTokens());

            sessionManager.addMessage(session.id(), AgentMessage.assistant(response));
            costTracker.record(session.id(), usage);
            return AgentLoopResult.success(response, 1, List.of(), usage);

        } catch (Exception e) {
            log.error("Agent loop failed for session {}", session.id(), e);
            return AgentLoopResult.failure(e.getMessage(), 0, List.of(), new TokenUsage());
        } finally {
            HarnessToolCallbackAdapter.clearContext();
        }
    }

    private ToolCallback[] buildToolCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();

        // 1. Custom HarnessTools (via ToolRegistry)
        toolRegistry.getAll().stream()
                .map(tool -> (ToolCallback) new HarnessToolCallbackAdapter(tool, toolExecutionService))
                .forEach(callbacks::add);

        // 2. MCP tools — Spring AI auto-creates ToolCallback beans for each MCP server
        Map<String, ToolCallback> mcpTools = applicationContext.getBeansOfType(ToolCallback.class);
        log.info("Agent tools: {} custom + {} MCP", callbacks.size(), mcpTools.size());
        callbacks.addAll(mcpTools.values());

        return callbacks.toArray(ToolCallback[]::new);
    }

    /** Extract {@link TokenUsage} from Spring AI's {@link ChatResponse} metadata. */
    private static TokenUsage extractUsage(ChatResponse resp) {
        if (resp == null || resp.getMetadata() == null) return new TokenUsage();
        var usage = resp.getMetadata().getUsage();
        if (usage == null) return new TokenUsage();
        return TokenUsage.of(
                usage.getPromptTokens(),
                usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0L);
    }
}
