package uct8086.ai.core.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import uct8086.ai.common.enums.ToolCategory;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import uct8086.ai.coordinator.AgentScope;
import uct8086.ai.coordinator.SubagentRegistry;
import uct8086.ai.core.engine.AgentEngine;
import uct8086.ai.core.engine.AgentLoopResult;
import uct8086.ai.core.session.SessionManager;

/**
 * Continue (or collect the result of) a sub-agent spawned with the {@code agent} tool.
 * Maps to OpenHarness's coordinator {@code send_message} primitive: the orchestrator
 * talks to a spawned agent by name.
 *
 * <ul>
 *   <li>If the sub-agent already finished, the message continues its conversation in its
 *       own session — the sub-agent remembers its full context (task, tools used, results).</li>
 *   <li>If the sub-agent is still running in the background (spawned with wait=false),
 *       this waits up to {@code wait_seconds} for it to finish and then delivers the
 *       message to it, returning its reply. This is how parallel sub-agent results are
 *       collected.</li>
 * </ul>
 *
 * <p>Arguments:
 * <ul>
 *   <li>{@code name} (required) — the sub-agent name given to the {@code agent} tool</li>
 *   <li>{@code message} (required) — the message to deliver</li>
 *   <li>{@code wait_seconds} (optional, default 120) — how long to wait for a still-running
 *       sub-agent before giving up (0 = do not wait)</li>
 * </ul>
 */
@Component
public class SendMessageTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(SendMessageTool.class);

    private final ObjectProvider<AgentEngine> agentEngineProvider;
    private final SessionManager sessionManager;
    private final SubagentRegistry subagentRegistry;

    public SendMessageTool(ObjectProvider<AgentEngine> agentEngineProvider,
                           SessionManager sessionManager,
                           SubagentRegistry subagentRegistry) {
        super("send_message",
                "Send a message to a sub-agent you spawned with the agent tool. If the "
                        + "sub-agent already finished, it continues the conversation in its own "
                        + "session with full context of its previous work. If it is still running "
                        + "in the background, this waits for it to finish (up to wait_seconds) "
                        + "and then delivers your message. Use it to collect results of parallel "
                        + "sub-agents or to ask follow-up questions.",
                ToolCategory.AGENT);
        this.agentEngineProvider = agentEngineProvider;
        this.sessionManager = sessionManager;
        this.subagentRegistry = subagentRegistry;
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> arguments, ToolExecutionContext context) throws Exception {
        String name = requireString(arguments, "name");
        String message = requireString(arguments, "message");
        int waitSeconds = optionalInt(arguments, "wait_seconds", 120);

        Long userId = sessionManager.resolveUserId(context.sessionId()).orElse(null);
        if (userId == null) {
            return ToolResult.error("Cannot send message: unable to resolve user for session "
                    + context.sessionId());
        }

        SubagentRegistry.SubagentState state = subagentRegistry.get(userId, name).orElse(null);
        if (state == null) {
            return ToolResult.error("No sub-agent named '" + name
                    + "'. Spawn one first with the agent tool.");
        }

        // A background sub-agent may still be running: wait for it (bounded), then deliver.
        if (state.status() == SubagentRegistry.Status.RUNNING) {
            log.info("Sub-agent '{}' still running, waiting up to {}s before delivering message",
                    name, waitSeconds);
            ToolResult awaited = awaitCompletion(state, name, waitSeconds);
            if (awaited != null) {
                return awaited;
            }
            if (state.status() != SubagentRegistry.Status.COMPLETED) {
                return statusError(state, name);
            }
        }

        switch (state.status()) {
            case COMPLETED -> {
                AgentEngine engine = agentEngineProvider.getIfAvailable();
                if (engine == null) {
                    return ToolResult.error("Cannot continue sub-agent: AgentEngine not initialized");
                }
                log.info("Continuing sub-agent '{}' in session {}", name, state.sessionId());
                AgentLoopResult result = engine.execute(userId, message, state.sessionId(), state.role(), AgentScope.SUBAGENT);
                subagentRegistry.markCompleted(state, result.response());

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("subagent", name);
                metadata.put("sessionId", state.sessionId());
                metadata.put("turns", result.turns());
                metadata.put("success", result.success());
                if (result.success()) {
                    return ToolResult.success(result.response(), metadata);
                }
                return ToolResult.error("Sub-agent '" + name + "' failed: " + result.error(), metadata);
            }
            case FAILED -> {
                return ToolResult.error("Sub-agent '" + name + "' failed earlier: "
                        + safe(state.lastResponse()) + ". Re-spawn it with the agent tool if needed.");
            }
            case STOPPED -> {
                return ToolResult.error("Sub-agent '" + name
                        + "' was stopped with task_stop. Re-spawn it with the agent tool to run a new task.");
            }
            default -> {
                return statusError(state, name);
            }
        }
    }

    /**
     * Block until the running sub-agent finishes or the wait budget is exhausted.
     *
     * @return an error ToolResult if it is still running / was stopped while waiting,
     *         or null if it finished and the caller should proceed.
     */
    private ToolResult awaitCompletion(SubagentRegistry.SubagentState state, String name, int waitSeconds)
            throws InterruptedException {
        var future = state.future();
        if (future == null || waitSeconds <= 0) {
            return ToolResult.error("Sub-agent '" + name + "' is still running"
                    + (waitSeconds <= 0 ? "" : " but has no wait handle")
                    + ". Retry in a moment or use task_stop.");
        }
        try {
            future.get(waitSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ToolResult.error("Sub-agent '" + name + "' is still running after " + waitSeconds
                    + "s. Retry later, raise wait_seconds, or use task_stop to abort it.");
        } catch (CancellationException e) {
            return ToolResult.error("Sub-agent '" + name + "' was stopped while running.");
        } catch (ExecutionException e) {
            log.warn("Background sub-agent '{}' ended with error: {}", name,
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            // The registry recorded the failure; fall through so the caller reports it.
        }
        return null;
    }

    private static ToolResult statusError(SubagentRegistry.SubagentState state, String name) {
        return ToolResult.error("Sub-agent '" + name + "' is in state " + state.status()
                + " and cannot receive messages. Re-spawn it with the agent tool if needed.");
    }

    private static String safe(String value) {
        return value == null ? "unknown error" : value;
    }
}
