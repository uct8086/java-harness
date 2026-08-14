package uct8086.ai.core.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
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

import uct8086.ai.common.enums.PermissionMode;
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
import uct8086.ai.metrics.ChatMetrics;

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
    private final ChatMetrics chatMetrics;

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
                       McpConnectionManager mcpConnectionManager,
                       ChatMetrics chatMetrics) {
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
        this.chatMetrics = chatMetrics;
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

    public AgentLoopResult execute(Long userId, String userPrompt, String sessionId) {
        return executeInternal(userId, userPrompt, sessionId, null);
    }

    public AgentLoopResult execute(Long userId, String userPrompt, String sessionId, String additionalContext) {
        return executeInternal(userId, userPrompt, sessionId, additionalContext);
    }

    // Bounded thread pool for streaming agent execution (avoids unbounded thread creation).
    private final ExecutorService streamExecutor = new ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            r -> {
                Thread t = new Thread(r, "uct8086-stream-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> log.error("Stream task rejected: executor queue full"));

    /**
     * Execute the agent loop asynchronously, pushing SSE events to the given emitter
     * token-by-token (true streaming). The caller (controller) returns immediately;
     * results arrive via the emitter as {@code token}, {@code tool} and {@code done} events.
     */
    public void executeStream(Long userId, String userPrompt, String sessionId,
                              SseEmitter emitter) {
        streamExecutor.submit(() -> executeInternalStream(userId, userPrompt, sessionId, emitter));
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
    private AgentLoopResult executeInternal(Long userId, String userPrompt, String sessionId, String additionalContext) {
        SessionManager.ConversationSession session = (sessionId != null)
                ? sessionManager.getSession(userId, sessionId).orElseGet(() -> sessionManager.createSession(userId))
                : sessionManager.createSession(userId);

        // Read the current default permission mode (per-request) instead of mutating
        // a global singleton field, which caused concurrent requests to overwrite
        // each other's mode.
        PermissionMode permissionMode = permissionChecker.getMode();
        Path workingDir = Path.of(properties.getWorkingDirectory());
        ToolExecutionContext context = new ToolExecutionContext(
                session.id(), workingDir, permissionMode);

        String baseSystemPrompt = (additionalContext != null)
                ? promptAssembler.buildSystemPrompt(additionalContext)
                : (properties.getSystemPrompt() != null
                        ? properties.getSystemPrompt()
                        : promptAssembler.buildSystemPrompt());
        final String systemPrompt = enrichWithRag(baseSystemPrompt, userPrompt);
        sessionManager.addMessage(userId, session.id(), AgentMessage.user(userPrompt));

        ToolCallback[] callbacks = buildToolCallbacks(userId, context);
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
                    .temperature(properties.getTemperature())
                    // Ask the (OpenAI-compatible) provider to include token usage in
                    // the final streaming chunk, so we can track real token counts.
                    .streamOptions(OpenAiChatOptions.StreamOptions.builder()
                            .includeUsage(true)
                            .build());

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

            sessionManager.addMessage(userId, session.id(), AgentMessage.assistant(response));
            costTracker.record(userId, session.id(), usage);
            chatMetrics.recordSuccess(elapsed, usage.inputTokens(), usage.outputTokens(), turns, toolCallRecords.size());
            return AgentLoopResult.success(response, turns, toolCallRecords, usage);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Agent loop failed for session {}", session.id(), e);
            chatMetrics.recordFailure(elapsed);
            return AgentLoopResult.failure(e.getMessage(), 0, List.of(), new TokenUsage());
        }
    }

    /**
     * Streaming variant of the agent loop: subscribes to Spring AI's {@code stream()}
     * Flux and pushes each text token (and tool-call events) to the SSE emitter in
     * real time, so the frontend renders text as it is generated (typewriter effect).
     */
    private void executeInternalStream(Long userId, String userPrompt, String sessionId,
                                       SseEmitter emitter) {
        SessionManager.ConversationSession session = (sessionId != null)
                ? sessionManager.getSession(userId, sessionId).orElseGet(() -> sessionManager.createSession(userId))
                : sessionManager.createSession(userId);

        PermissionMode permissionMode = permissionChecker.getMode();
        Path workingDir = Path.of(properties.getWorkingDirectory());
        ToolExecutionContext context = new ToolExecutionContext(session.id(), workingDir, permissionMode);

        String baseSystemPrompt = properties.getSystemPrompt() != null
                        ? properties.getSystemPrompt()
                        : promptAssembler.buildSystemPrompt();
        final String systemPrompt = enrichWithRag(baseSystemPrompt, userPrompt);
        sessionManager.addMessage(userId, session.id(), AgentMessage.user(userPrompt));

        ToolCallback[] callbacks = buildToolCallbacks(userId, context);
        long startTime = System.currentTimeMillis();

        // Accumulators for the full response (tokens arrive as deltas).
        StringBuilder fullText = new StringBuilder();
        final long[] inputTokens = {0};
        final long[] outputTokens = {0};
        final int[] toolCallCount = {0};

        try {
            HarnessToolCallingAdvisor harnessAdvisor = new HarnessToolCallingAdvisor(
                    ToolCallingManager.builder().build(),
                    ToolCallingAdvisor.DEFAULT_ORDER,
                    true);

            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                    .toolCallbacks(List.of(callbacks))
                    .model(properties.getModel())
                    .temperature(properties.getTemperature())
                    // Ask the (OpenAI-compatible) provider to include token usage in
                    // the final streaming chunk, so we can track real token counts.
                    .streamOptions(OpenAiChatOptions.StreamOptions.builder()
                            .includeUsage(true)
                            .build());

            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(systemPrompt)
                    .defaultOptions(optionsBuilder)
                    .defaultAdvisors(harnessAdvisor)
                    .build();

            // Push the session id first so the frontend can track the conversation.
            emitter.send(SseEmitter.event().name("session")
                    .data(java.util.Map.of("sessionId", session.id())));

            Flux<ChatClientResponse> flux = chatClient.prompt()
                    .user(userPrompt)
                    .stream()
                    .chatClientResponse();

            flux.doOnNext(clientResponse -> {
                ChatResponse chatResponse = clientResponse.chatResponse();
                if (chatResponse == null) return;

                // Track token usage if metadata is present.
                TokenUsage usage = extractUsage(chatResponse);
                if (usage.inputTokens() > 0) inputTokens[0] = usage.inputTokens();
                if (usage.outputTokens() > 0) outputTokens[0] = usage.outputTokens();

                Generation generation = chatResponse.getResult();
                if (generation == null) return;
                AssistantMessage output = generation.getOutput();

                // Tool call round: signal the frontend and don't append to text.
                if (output.hasToolCalls()) {
                    output.getToolCalls().forEach(tc -> {
                        toolCallCount[0]++;
                        try {
                            emitter.send(SseEmitter.event().name("tool")
                                    .data(java.util.Map.of("name", tc.name())));
                        } catch (Exception ignored) {
                        }
                    });
                    return;
                }

                // Text delta: append and push as a token event.
                String delta = output.getText();
                if (delta != null && !delta.isEmpty()) {
                    fullText.append(delta);
                    try {
                        emitter.send(SseEmitter.event().name("token")
                                .data(java.util.Map.of("text", delta)));
                    } catch (Exception ignored) {
                    }
                }
            }).doOnComplete(() -> {
                try {
                    String response = fullText.toString();

                    // Fallback: if the streaming provider did not return usage metadata
                    // (DeepSeek streaming omits usage without stream_options.include_usage),
                    // estimate tokens so cost tracking still works.
                    long in = inputTokens[0] > 0 ? inputTokens[0]
                            : estimateTokens(systemPrompt) + estimateTokens(userPrompt);
                    long out = outputTokens[0] > 0 ? outputTokens[0]
                            : estimateTokens(response);

                    TokenUsage usage = TokenUsage.of(in, out);
                    sessionManager.addMessage(userId, session.id(), AgentMessage.assistant(response));
                    costTracker.record(userId, session.id(), usage);
                    chatMetrics.recordSuccess(System.currentTimeMillis() - startTime,
                            in, out, 1, toolCallCount[0]);

                    final long finalIn = in;
                    final long finalOut = out;
                    final long finalTotal = usage.totalTokens();
                    final double finalCost = usage.cost();
                    emitter.send(SseEmitter.event().name("done")
                            .data(java.util.Map.of(
                                    "sessionId", session.id(),
                                    "inputTokens", finalIn,
                                    "outputTokens", finalOut,
                                    "totalTokens", finalTotal,
                                    "cost", finalCost)));
                    emitter.complete();
                } catch (Exception e) {
                    log.error("Stream completion failed", e);
                    emitter.completeWithError(e);
                }
            }).doOnError(e -> {
                log.error("Agent stream failed for session {}", session.id(), e);
                chatMetrics.recordFailure(System.currentTimeMillis() - startTime);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(java.util.Map.of("message",
                                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }).subscribe();

        } catch (Exception e) {
            log.error("Failed to start agent stream for session {}", session.id(), e);
            chatMetrics.recordFailure(System.currentTimeMillis() - startTime);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(java.util.Map.of("message",
                                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            } catch (Exception ignored) {
            }
            emitter.completeWithError(e);
        }
    }

    private ToolCallback[] buildToolCallbacks(Long userId, ToolExecutionContext context) {
        List<ToolCallback> callbacks = new ArrayList<>();

        // 1. Custom HarnessTools (via ToolRegistry)
        toolRegistry.getAll().stream()
                .map(tool -> (ToolCallback) new HarnessToolCallbackAdapter(tool, toolExecutionService, context))
                .forEach(callbacks::add);

        // 2. MCP tools — dynamically connected from the user's own MCP servers
        List<ToolCallback> mcpCallbacks = mcpConnectionManager.getToolCallbacks(userId);
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

    /**
     * Rough token estimate for a string, used as a fallback when the streaming
     * provider does not return usage metadata (DeepSeek streaming returns usage only
     * in the final chunk, and only when {@code stream_options.include_usage} is set —
     * which Spring AI does not pass by default).
     *
     * <p>Heuristic: mixed CJK/Latin text averages ~2 chars per token, so divide by 2.
     * This is intentionally approximate; cost accounting remains approximately correct.
     */
    private static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0L;
        return Math.max(1L, text.length() / 2L);
    }
}
