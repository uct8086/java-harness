package uct8086.ai.core.engine;

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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * The core agent engine implementing the Agent Loop.
 * Maps to OpenHarness's QueryEngine / Agent Loop.
 *
 * <p>The agent loop:
 * <ol>
 *   <li>Build system prompt (with tools, skills, memory)</li>
 *   <li>Call the model with user prompt and tool callbacks</li>
 *   <li>If model requests tool calls, execute them through the pipeline</li>
 *   <li>Send results back to the model</li>
 *   <li>Repeat until model is done or max turns reached</li>
 * </ol>
 *
 * <p>Tool execution pipeline per call:
 * Permission → PreToolUse Hook → Execute → PostToolUse Hook
 */
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

    public AgentEngine(ChatModel chatModel,
                       ToolRegistry toolRegistry,
                       ToolExecutionService toolExecutionService,
                       PromptAssembler promptAssembler,
                       SessionManager sessionManager,
                       CostTracker costTracker,
                       PermissionChecker permissionChecker,
                       HarnessProperties properties) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.promptAssembler = promptAssembler;
        this.sessionManager = sessionManager;
        this.costTracker = costTracker;
        this.permissionChecker = permissionChecker;
        this.properties = properties;
    }

    /**
     * Execute a single prompt through the agent loop.
     *
     * @param userPrompt the user's input prompt
     * @param sessionId optional session ID for context (null = new session)
     * @return the agent loop result
     */
    public AgentLoopResult execute(String userPrompt, String sessionId) {
        // Create or get session
        SessionManager.ConversationSession session = (sessionId != null)
                ? sessionManager.getSession(sessionId).orElseGet(() -> sessionManager.createSession())
                : sessionManager.createSession();

        // Set permission mode from config
        permissionChecker.setMode(properties.getPermissionMode());

        // Build execution context
        Path workingDir = Path.of(properties.getWorkingDirectory());
        ToolExecutionContext context = new ToolExecutionContext(
                session.getId(),
                workingDir,
                properties.getPermissionMode()
        );

        // Build system prompt
        String systemPrompt = properties.getSystemPrompt() != null
                ? properties.getSystemPrompt()
                : promptAssembler.buildSystemPrompt();

        // Record user message
        sessionManager.addMessage(session.getId(), AgentMessage.user(userPrompt));

        // Build tool callbacks
        ToolCallback[] callbacks = buildToolCallbacks();

        log.info("Starting agent loop for session {} (tools: {})", session.getId(), callbacks.length);
        log.debug("System prompt: {}", systemPrompt != null && systemPrompt.length() > 500 ? systemPrompt.substring(0, 500) + "..." : systemPrompt);

        long startTime = System.currentTimeMillis();

        try {
            // Execute using ChatClient with tool calling
            HarnessToolCallbackAdapter.setContext(context);

            ChatClient chatClient = ChatClient.create(chatModel);

            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .tools(callbacks)
                    .call()
                    .content();

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Model responded in {}ms, response length: {} chars", elapsed, response != null ? response.length() : 0);
            log.debug("Model response: {}", response != null && response.length() > 500 ? response.substring(0, 500) + "..." : response);

            // Record assistant message
            sessionManager.addMessage(session.getId(), AgentMessage.assistant(response));

            // Track usage (simplified - actual token usage would come from ChatResponse)
            TokenUsage usage = new TokenUsage();
            costTracker.record(session.getId(), usage);

            log.info("Agent loop completed for session {} in {}ms", session.getId(), System.currentTimeMillis() - startTime);

            return AgentLoopResult.success(response, 1, List.of(), usage);

        } catch (Exception e) {
            log.error("Agent loop failed for session {} after {}ms", session.getId(), System.currentTimeMillis() - startTime, e);
            return AgentLoopResult.failure(e.getMessage(), 0, List.of(), new TokenUsage());
        } finally {
            HarnessToolCallbackAdapter.clearContext();
        }
    }

    /**
     * Execute a prompt with additional context (skills, memory).
     */
    public AgentLoopResult execute(String userPrompt, String sessionId, String additionalContext) {
        // Create or get session
        SessionManager.ConversationSession session = (sessionId != null)
                ? sessionManager.getSession(sessionId).orElseGet(() -> sessionManager.createSession())
                : sessionManager.createSession();

        permissionChecker.setMode(properties.getPermissionMode());
        Path workingDir = Path.of(properties.getWorkingDirectory());
        ToolExecutionContext context = new ToolExecutionContext(
                session.getId(),
                workingDir,
                properties.getPermissionMode()
        );

        String systemPrompt = promptAssembler.buildSystemPrompt(additionalContext);
        sessionManager.addMessage(session.getId(), AgentMessage.user(userPrompt));

        ToolCallback[] callbacks = buildToolCallbacks();
        log.info("Starting agent loop for session {} (tools: {}, with context)", session.getId(), callbacks.length);
        log.debug("Additional context: {}", additionalContext);

        long startTime = System.currentTimeMillis();

        try {
            HarnessToolCallbackAdapter.setContext(context);
            ChatClient chatClient = ChatClient.create(chatModel);

            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .tools(callbacks)
                    .call()
                    .content();

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Model responded in {}ms, response length: {} chars", elapsed, response != null ? response.length() : 0);
            log.debug("Model response: {}", response != null && response.length() > 500 ? response.substring(0, 500) + "..." : response);

            sessionManager.addMessage(session.getId(), AgentMessage.assistant(response));
            TokenUsage usage = new TokenUsage();
            costTracker.record(session.getId(), usage);

            log.info("Agent loop completed for session {} in {}ms", session.getId(), System.currentTimeMillis() - startTime);

            return AgentLoopResult.success(response, 1, List.of(), usage);

        } catch (Exception e) {
            log.error("Agent loop failed for session {} after {}ms", session.getId(), System.currentTimeMillis() - startTime, e);
            return AgentLoopResult.failure(e.getMessage(), 0, List.of(), new TokenUsage());
        } finally {
            HarnessToolCallbackAdapter.clearContext();
        }
    }

    /**
     * Build tool callbacks from registered tools.
     */
    private ToolCallback[] buildToolCallbacks() {
        return toolRegistry.getAll().stream()
                .map(tool -> (ToolCallback) new HarnessToolCallbackAdapter(tool, toolExecutionService))
                .toArray(ToolCallback[]::new);
    }
}
