package uct8086.ai.core.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

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
import uct8086.ai.mcp.McpConnectionManager;

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
    private final McpConnectionManager mcpConnectionManager;

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
                       ApplicationContext applicationContext,
                       McpConnectionManager mcpConnectionManager) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.promptAssembler = promptAssembler;
        this.sessionManager = sessionManager;
        this.costTracker = costTracker;
        this.permissionChecker = permissionChecker;
        this.properties = properties;
        this.applicationContext = applicationContext;
        this.mcpConnectionManager = mcpConnectionManager;
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
        return executeInternal(userPrompt, sessionId, null);
    }

    public AgentLoopResult execute(String userPrompt, String sessionId, String additionalContext) {
        return executeInternal(userPrompt, sessionId, additionalContext);
    }

    /**
     * Core agent loop using Spring AI 2.0's official {@link ToolCallingAdvisor}
     * with a custom {@link HarnessToolCallingAdvisor} wrapper to capture turn/tool-call metrics.
     * <p>
     * Tool callbacks are injected via {@link OpenAiChatOptions} on the Prompt, which is the
     * standard Spring AI 2.0 mechanism. The {@link ToolCallingAdvisor} reads them from
     * {@code ToolCallingChatOptions} at runtime.
     */
    @SuppressWarnings("unchecked")
    private AgentLoopResult executeInternal(String userPrompt, String sessionId, String additionalContext) {
        SessionManager.ConversationSession session = (sessionId != null)
                ? sessionManager.getSession(sessionId).orElseGet(() -> sessionManager.createSession())
                : sessionManager.createSession();

        permissionChecker.setMode(properties.getPermissionMode());
        Path workingDir = Path.of(properties.getWorkingDirectory());
        ToolExecutionContext context = new ToolExecutionContext(
                session.id(), workingDir, properties.getPermissionMode());

        String systemPrompt = (additionalContext != null)
                ? promptAssembler.buildSystemPrompt(additionalContext)
                : (properties.getSystemPrompt() != null
                        ? properties.getSystemPrompt()
                        : promptAssembler.buildSystemPrompt());
        systemPrompt = enrichWithRag(systemPrompt, userPrompt);
        sessionManager.addMessage(session.id(), AgentMessage.user(userPrompt));

        ToolCallback[] callbacks = buildToolCallbacks(context);
        int maxTurns = properties.getMaxTurns();
        log.info("Starting agent loop for session {} (tools: {}, maxTurns: {})",
                session.id(), callbacks.length, maxTurns);

        long startTime = System.currentTimeMillis();

        try {
            // Build the metrics-capturing advisor wrapping the official Spring AI ToolCallingAdvisor
            HarnessToolCallingAdvisor harnessAdvisor = new HarnessToolCallingAdvisor(
                    ToolCallingManager.builder().build(),
                    ToolCallingAdvisor.DEFAULT_ORDER,
                    true);

            // Build default ChatOptions with tool definitions (OpenAiChatOptions extends ToolCallingChatOptions)
            // NOTE: defaultOptions() accepts ChatOptions.Builder (raw type), NOT ChatOptions instance
            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                    .toolCallbacks(List.of(callbacks))
                    .model(properties.getModel())
                    .temperature(properties.getTemperature());

            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(systemPrompt)
                    .defaultOptions(optionsBuilder)
                    .defaultAdvisors(harnessAdvisor)
                    .build();

            ChatClientResponse clientResponse = chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .chatClientResponse();

            // Extract final response text
            ChatResponse chatResponse = clientResponse.chatResponse();
            String response = chatResponse != null && chatResponse.getResult() != null
                    && chatResponse.getResult().getOutput() != null
                    && chatResponse.getResult().getOutput().getText() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : "";

            TokenUsage usage = extractUsage(chatResponse);

            // Extract metrics captured by HarnessToolCallingAdvisor
            List<AgentLoopResult.ToolCallRecord> toolCallRecords =
                    (List<AgentLoopResult.ToolCallRecord>) clientResponse.context()
                            .getOrDefault(HarnessToolCallingAdvisor.CONTEXT_TOOL_CALL_RECORDS,
                                    Collections.emptyList());

            int[] turnCounter = (int[]) clientResponse.context()
                    .get(HarnessToolCallingAdvisor.CONTEXT_TURNS);
            int turns = (turnCounter != null) ? turnCounter[0] : 1;

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Agent loop finished: {} turns, {} tool calls, {}ms, in={} out={}",
                    turns, toolCallRecords.size(), elapsed,
                    usage.inputTokens(), usage.outputTokens());

            sessionManager.addMessage(session.id(), AgentMessage.assistant(response));
            costTracker.record(session.id(), usage);
            return AgentLoopResult.success(response, turns, toolCallRecords, usage);

        } catch (Exception e) {
            log.error("Agent loop failed for session {}", session.id(), e);
            return AgentLoopResult.failure(e.getMessage(), 0, List.of(), new TokenUsage());
        }
    }

    private ToolCallback[] buildToolCallbacks(ToolExecutionContext context) {
        List<ToolCallback> callbacks = new ArrayList<>();

        // 1. Custom HarnessTools (via ToolRegistry)
        toolRegistry.getAll().stream()
                .map(tool -> (ToolCallback) new HarnessToolCallbackAdapter(tool, toolExecutionService, context))
                .forEach(callbacks::add);

        // 2. MCP tools — dynamically connected from .uct8086/mcp-servers.json
        List<ToolCallback> mcpCallbacks = mcpConnectionManager.getToolCallbacks();
        log.info("Agent tools: {} custom + {} MCP", callbacks.size(), mcpCallbacks.size());
        callbacks.addAll(mcpCallbacks);

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
