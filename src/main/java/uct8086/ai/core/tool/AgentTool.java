package uct8086.ai.core.tool;

import jakarta.annotation.PreDestroy;
import uct8086.ai.common.enums.ToolCategory;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import uct8086.ai.coordinator.AgentScope;
import uct8086.ai.coordinator.SubagentRegistry;
import uct8086.ai.core.engine.AgentEngine;
import uct8086.ai.core.engine.AgentLoopResult;
import uct8086.ai.core.session.SessionManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sub-agent delegation tool. This is the primitive that turns the main agent into an
 * orchestrator: the main agent calls {@code agent} to delegate a subtask to a sub-agent,
 * which runs in its own isolated session with its own role system prompt. The sub-agent's
 * result is returned to the main agent so it can decide the next step.
 *
 * <p>This maps to OpenHarness's coordinator {@code agent} tool (the "spawn/delegate"
 * primitive), where decision authority stays with the orchestrating agent and the
 * harness only provides the delegation primitive.
 *
 * <p>Each spawned sub-agent is registered in the {@link SubagentRegistry} under its
 * {@code name}, keeping a dedicated session. That makes two follow-up primitives
 * possible: {@code send_message} (continue a finished sub-agent in the same session)
 * and {@code task_stop} (abort a background sub-agent).
 *
 * <p>Arguments:
 * <ul>
 *   <li>{@code name} (required) — a short identifier for the sub-agent</li>
 *   <li>{@code role} (required) — the sub-agent's role/system prompt describing its expertise</li>
 *   <li>{@code task} (required) — the task to delegate to the sub-agent</li>
 *   <li>{@code wait} (optional, default true) — wait for the sub-agent to finish and return
 *       its result. Set to false to start it in the background, which allows running
 *       several sub-agents in parallel; fetch results later with {@code send_message}.</li>
 * </ul>
 */
@Component
public class AgentTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AgentTool.class);

    private final ObjectProvider<AgentEngine> agentEngineProvider;
    private final SessionManager sessionManager;
    private final SubagentRegistry subagentRegistry;

    /** Executes background (wait=false) sub-agents, enabling parallel delegation. */
    private final ExecutorService subagentExecutor = new ThreadPoolExecutor(
            2, 6, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(20),
            r -> {
                Thread t = new Thread(r, "uct8086-subagent-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            });

    public AgentTool(ObjectProvider<AgentEngine> agentEngineProvider,
                     SessionManager sessionManager,
                     SubagentRegistry subagentRegistry) {
        super("agent",
                "Delegate a subtask to a sub-agent that runs in its own isolated session "
                        + "with a specialized role. Use this to break a complex task into "
                        + "parts and have a sub-agent handle each part. By default the call "
                        + "waits and returns the sub-agent's final answer. Set wait=false to "
                        + "start it in the background (call agent several times to run "
                        + "sub-agents in parallel), then use send_message to fetch each "
                        + "result and task_stop to abort one if needed.",
                ToolCategory.AGENT);
        this.agentEngineProvider = agentEngineProvider;
        this.sessionManager = sessionManager;
        this.subagentRegistry = subagentRegistry;
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> arguments, ToolExecutionContext context) throws Exception {
        String name = requireString(arguments, "name");
        String role = requireString(arguments, "role");
        String task = requireString(arguments, "task");
        boolean wait = optionalBool(arguments, "wait", true);

        // Resolve the owning user from the orchestrator's session so the sub-agent is
        // billed and persisted under the same user.
        Long userId = sessionManager.resolveUserId(context.sessionId()).orElse(null);
        if (userId == null) {
            return ToolResult.error("Cannot delegate: unable to resolve user for session "
                    + context.sessionId());
        }

        AgentEngine engine = agentEngineProvider.getIfAvailable();
        if (engine == null) {
            return ToolResult.error("Cannot delegate: AgentEngine not initialized");
        }

        // The sub-agent gets its OWN session (created up front and registered under the
        // agent name) so its conversation stays isolated from the orchestrator's history,
        // while send_message can later continue it with full context. The role system
        // prompt is injected via additionalContext on every execution.
        SessionManager.ConversationSession subSession =
                sessionManager.createSession(userId, "subagent:" + name, true);

        SubagentRegistry.SubagentState state;
        try {
            state = subagentRegistry.register(userId, name, role, subSession.id());
        } catch (IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        }

        log.info("Orchestrator delegating to sub-agent '{}' (userId={}, sessionId={}, wait={})",
                name, userId, subSession.id(), wait);

        if (wait) {
            return toToolResult(name, runAndTrack(engine, userId, state, task), subSession.id());
        }

        // Background mode: return immediately, the sub-agent keeps running.
        try {
            Future<?> future = subagentExecutor.submit(() -> runAndTrack(engine, userId, state, task));
            state.attachFuture(future);
        } catch (RejectedExecutionException e) {
            subagentRegistry.markFailed(state, "sub-agent executor is saturated");
            return ToolResult.error("Cannot start sub-agent '" + name + "': too many sub-agents "
                    + "are already queued. Wait for running ones to finish.");
        }
        return ToolResult.success("Sub-agent '" + name + "' started in the background (session "
                + subSession.id() + "). Continue with other work, then call send_message(name='"
                + name + "', message='...') to get its result, or task_stop(name='" + name
                + "') to abort it.",
                Map.of("subagent", name, "sessionId", subSession.id(), "async", true));
    }

    /**
     * Run the sub-agent through the engine and keep the registry status in sync.
     * Interrupted background runs resolve to a stopped state (terminal).
     */
    private AgentLoopResult runAndTrack(AgentEngine engine, Long userId,
                                        SubagentRegistry.SubagentState state, String task) {
        try {
            AgentLoopResult result = engine.execute(userId, task, state.sessionId(), state.role(), AgentScope.SUBAGENT);
            if (result.success()) {
                subagentRegistry.markCompleted(state, result.response());
            } else {
                subagentRegistry.markFailed(state, result.error());
            }
            return result;
        } catch (Exception e) {
            // Note: a sub-agent stopped via task_stop is already in the terminal STOPPED
            // state, so the markFailed below is a no-op for it.
            log.warn("Sub-agent '{}' failed: {}", state.name(), e.getMessage());
            subagentRegistry.markFailed(state, e.getMessage());
            return AgentLoopResult.failure("Sub-agent '" + state.name() + "' failed: " + e.getMessage(),
                    0, java.util.List.of(), new uct8086.ai.common.model.TokenUsage());
        }
    }

    private static ToolResult toToolResult(String name, AgentLoopResult result, String sessionId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("subagent", name);
        metadata.put("sessionId", sessionId);
        metadata.put("turns", result.turns());
        metadata.put("success", result.success());
        if (result.tokenUsage() != null) {
            metadata.put("inputTokens", result.tokenUsage().inputTokens());
            metadata.put("outputTokens", result.tokenUsage().outputTokens());
        }
        if (result.success()) {
            return ToolResult.success(result.response(), metadata);
        }
        return ToolResult.error("Sub-agent '" + name + "' failed: " + result.error(), metadata);
    }

    private static boolean optionalBool(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = arguments.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString());
    }

    @PreDestroy
    void shutdownExecutor() {
        subagentExecutor.shutdownNow();
    }
}
