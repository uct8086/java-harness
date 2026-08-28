package uct8086.ai.core.tool;

import java.util.LinkedHashMap;
import java.util.Map;
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
import uct8086.ai.tasks.BackgroundTask;
import uct8086.ai.tasks.TaskManager;

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
 * <h2>Distributed coordination</h2>
 * <p>Previously this tool blocked on a local {@code java.util.concurrent.Future} stored
 * on the sub-agent's in-memory {@link SubagentRegistry.SubagentState}. That handle only
 * existed on the JVM that spawned the sub-agent, so issuing {@code send_message} from
 * any other instance returned "no sub-agent named X".
 *
 * <p>Now the wait is driven by polling the distributed {@link TaskManager}'s task
 * state (Redis Hash {@code harness:task:{userId}}) until the worker reports
 * COMPLETED / FAILED / CANCELLED, or the wait budget is exhausted. The sub-agent's
 * final response is read back from {@link SubagentRegistry} (also Redis-backed) so it
 * is visible regardless of which instance ran the sub-agent.
 *
 * <h2>Arguments</h2>
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

    /** Poll interval when waiting for a distributed sub-agent task to finish. */
    private static final long POLL_INTERVAL_MS = 500L;

    private final ObjectProvider<AgentEngine> agentEngineProvider;
    private final SessionManager sessionManager;
    private final SubagentRegistry subagentRegistry;
    private final TaskManager taskManager;

    public SendMessageTool(ObjectProvider<AgentEngine> agentEngineProvider,
                           SessionManager sessionManager,
                           SubagentRegistry subagentRegistry,
                           TaskManager taskManager) {
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
        this.taskManager = taskManager;
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

        // A background sub-agent may still be running on a worker. Wait for the
        // distributed task to reach a terminal state before continuing the conversation.
        if (state.status() == SubagentRegistry.Status.RUNNING) {
            log.info("Sub-agent '{}' still running (taskId={}), waiting up to {}s",
                    name, state.taskId(), waitSeconds);
            WaitOutcome outcome = awaitDistributedCompletion(userId, name, state.taskId(), waitSeconds);
            switch (outcome) {
                case STILL_RUNNING -> {
                    return ToolResult.error("Sub-agent '" + name + "' is still running after "
                            + waitSeconds + "s. Retry later, raise wait_seconds, or use task_stop "
                            + "to abort it.");
                }
                case CANCELLED -> {
                    return ToolResult.error("Sub-agent '" + name + "' was stopped while running.");
                }
                case TASK_VANISHED -> {
                    return ToolResult.error("Sub-agent '" + name
                            + "' task vanished from the queue. Re-spawn it with the agent tool.");
                }
                case READY, FAILED_WHILE_WAITING -> {
                    // fall through: re-read fresh state and proceed
                }
            }
            // Re-read state — the worker wrote back COMPLETED/FAILED + lastResponse.
            state = subagentRegistry.get(userId, name).orElse(state);
            if (state.status() == SubagentRegistry.Status.RUNNING) {
                // Worker finished the task but the registry still shows RUNNING (race).
                // Treat as still running and surface a recoverable error.
                return ToolResult.error("Sub-agent '" + name + "' is still running (task finished "
                        + "but registry not yet updated). Retry in a moment.");
            }
            if (state.status() == SubagentRegistry.Status.STOPPED) {
                return ToolResult.error("Sub-agent '" + name
                        + "' was stopped with task_stop. Re-spawn it with the agent tool to run a new task.");
            }
            if (state.status() == SubagentRegistry.Status.FAILED) {
                return ToolResult.error("Sub-agent '" + name + "' failed earlier: "
                        + safe(state.lastResponse()) + ". Re-spawn it with the agent tool if needed.");
            }
            // COMPLETED: fall through to continue the conversation.
        }

        switch (state.status()) {
            case COMPLETED -> {
                AgentEngine engine = agentEngineProvider.getIfAvailable();
                if (engine == null) {
                    return ToolResult.error("Cannot continue sub-agent: AgentEngine not initialized");
                }
                log.info("Continuing sub-agent '{}' in session {}", name, state.sessionId());
                AgentLoopResult result = engine.execute(
                        userId, message, state.sessionId(), state.role(), AgentScope.SUBAGENT);
                subagentRegistry.markCompleted(state, result.response());

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("subagent", name);
                metadata.put("sessionId", state.sessionId());
                metadata.put("taskId", state.taskId());
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

    private enum WaitOutcome {
        READY,              // task reached COMPLETED
        FAILED_WHILE_WAITING, // task reached FAILED
        STILL_RUNNING,      // wait budget exhausted, still RUNNING/PENDING
        CANCELLED,          // task reached CANCELLED
        TASK_VANISHED       // task not found in TaskManager
    }

    /**
     * Poll the distributed {@link TaskManager} for the sub-agent's task until it reaches
     * a terminal state or the wait budget is exhausted. The registry's RUNNING entry is
     * the source of truth for "is the sub-agent still busy"; the TaskManager task tells
     * us when the actual work finished.
     */
    private WaitOutcome awaitDistributedCompletion(Long userId, String name, String taskId, int waitSeconds) {
        if (taskId == null || taskId.isBlank()) {
            // Legacy / unregistered path: no distributed task to wait on. Surface a
            // recoverable error so the caller can retry or re-spawn.
            log.warn("Sub-agent '{}' has no taskId; cannot await distributed completion", name);
            return WaitOutcome.TASK_VANISHED;
        }
        if (waitSeconds <= 0) {
            return WaitOutcome.STILL_RUNNING;
        }
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            BackgroundTask t = taskManager.getTask(userId, taskId).orElse(null);
            if (t == null) {
                return WaitOutcome.TASK_VANISHED;
            }
            switch (t.status()) {
                case COMPLETED -> { return WaitOutcome.READY; }
                case FAILED -> { return WaitOutcome.FAILED_WHILE_WAITING; }
                case CANCELLED -> { return WaitOutcome.CANCELLED; }
                default -> { /* PENDING / RUNNING */ }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return WaitOutcome.STILL_RUNNING;
            }
        }
        return WaitOutcome.STILL_RUNNING;
    }

    private static ToolResult statusError(SubagentRegistry.SubagentState state, String name) {
        return ToolResult.error("Sub-agent '" + name + "' is in state " + state.status()
                + " and cannot receive messages. Re-spawn it with the agent tool if needed.");
    }

    private static String safe(String value) {
        return value == null ? "unknown error" : value;
    }
}
