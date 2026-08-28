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
import uct8086.ai.tasks.BackgroundTask;
import uct8086.ai.tasks.TaskManager;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <h2>Distributed dispatch</h2>
 * <p>Previously this tool spawned the sub-agent on a local in-process thread pool
 * ({@code subagentExecutor}). That design worked in single-instance deployments but
 * broke under horizontal scaling: the spawned session id, the {@code Future} handle,
 * and the {@code SubagentRegistry} were all local to the JVM that created them, so
 * {@code send_message} / {@code task_stop} issued from another instance could not
 * find the sub-agent.
 *
 * <p>Now the sub-agent is dispatched through {@link TaskManager} (Redis Stream +
 * Consumer Group), so any worker instance in the deployment can pick up and execute
 * the subtask. The sub-agent's state (role, sessionId, taskId, status, lastResponse)
 * is persisted in Redis via {@link SubagentRegistry}, visible to all instances.
 *
 * <p>Each spawned sub-agent is registered in the {@link SubagentRegistry} under its
 * {@code name}, keeping a dedicated session id. That makes two follow-up primitives
 * possible: {@code send_message} (continue a finished sub-agent in the same session)
 * and {@code task_stop} (abort a background sub-agent via the distributed cancel flag).
 *
 * <h2>Arguments</h2>
 * <ul>
 *   <li>{@code name} (required) — a short identifier for the sub-agent</li>
 *   <li>{@code role} (required) — the sub-agent's role/system prompt describing its expertise</li>
 *   <li>{@code task} (required) — the task to delegate to the sub-agent</li>
 *   <li>{@code wait} (optional, default true) — wait for the sub-agent to finish and return
 *       its result. Set to false to start it in the background, which allows running
 *       several sub-agents in parallel; fetch results later with {@code send_message}.</li>
 * </ul>
 *
 * <p><b>Implementation note on {@code wait=true}:</b> in distributed mode there is no
 * local {@code Future} to block on. The orchestrator polls the {@link TaskManager}
 * task state (with bounded retries) until the worker reports COMPLETED/FAILED, then
 * returns the sub-agent's response to the model. If the worker crashes mid-flight the
 * pending Redis Stream message is reclaimed by another consumer, so the call eventually
 * resolves (or times out with a recoverable error). For {@code wait=false} the tool
 * returns immediately after enqueuing; the orchestrator later uses {@code send_message}
 * to fetch the result.
 */
@Component
public class AgentTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AgentTool.class);

    /**
     * How long (seconds) to poll a synchronous ({@code wait=true}) sub-agent's task
     * state before giving up and returning a recoverable error to the model. The model
     * can then call {@code send_message} to wait longer or pick up the result later.
     */
    private static final int SYNC_WAIT_SECONDS = 300;
    /** Poll interval for the synchronous wait loop. */
    private static final long POLL_INTERVAL_MS = 500L;

    private final ObjectProvider<AgentEngine> agentEngineProvider;
    private final SessionManager sessionManager;
    private final SubagentRegistry subagentRegistry;
    private final TaskManager taskManager;

    public AgentTool(ObjectProvider<AgentEngine> agentEngineProvider,
                     SessionManager sessionManager,
                     SubagentRegistry subagentRegistry,
                     TaskManager taskManager) {
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
        this.taskManager = taskManager;
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

        // Register the sub-agent up front. If a previous agent with the same name is
        // still RUNNING we surface the error to the model so it can decide what to do.
        SubagentRegistry.SubagentState state;
        try {
            // Register the sub-agent entry up front (taskId will be filled in once the
            // TaskManager assigns one). If a previous agent with the same name is still
            // RUNNING we surface the error to the model so it can decide what to do.
            state = subagentRegistry.register(userId, name, role, subSession.id());
        } catch (IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        }

        log.info("Orchestrator delegating to sub-agent '{}' (userId={}, sessionId={}, wait={})",
                name, userId, subSession.id(), wait);

        // Enqueue the subtask on the Redis Stream via TaskManager. Any worker in the
        // consumer group can pick it up and run it through AgentEngine with scope=SUBAGENT.
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("role", role);
        payload.put("task", task);
        payload.put("sessionId", subSession.id());
        BackgroundTask bgTask = taskManager.createTask(
                userId, name, "Subagent task: " + task, "SUBTASK", payload);

        // Persist the taskId back into the registry so send_message / task_stop can
        // drive the distributed task (await/cancel) by name.
        subagentRegistry.updateTaskId(userId, name, bgTask.id());

        if (wait) {
            // Synchronous: block on the distributed task completion.
            return waitAndCollect(name, userId, bgTask.id(), subSession.id());
        }

        // Background mode: return immediately, the sub-agent keeps running on a worker.
        return ToolResult.success("Sub-agent '" + name + "' started in the background (task "
                + bgTask.id() + ", session " + subSession.id()
                + "). Continue with other work, then call send_message(name='" + name
                + "', message='...') to get its result, or task_stop(name='" + name
                + "') to abort it.",
                Map.of("subagent", name, "sessionId", subSession.id(),
                        "taskId", bgTask.id(), "async", true));
    }

    /**
     * Poll the TaskManager for the sub-agent's task until it finishes (or the wait
     * budget is exhausted). The distributed worker, when it finishes, will have
     * recorded the sub-agent's response in the {@link SubagentRegistry} via
     * {@code AgentCoordinator}'s SUBTASK handler.
     */
    private ToolResult waitAndCollect(String name, Long userId, String taskId, String sessionId) {
        long deadline = System.currentTimeMillis() + SYNC_WAIT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            BackgroundTask t = taskManager.getTask(userId, taskId).orElse(null);
            if (t == null) {
                return ToolResult.error("Sub-agent '" + name + "' task vanished from the queue "
                        + "(taskId=" + taskId + "). Try re-spawning.");
            }
            switch (t.status()) {
                case COMPLETED -> {
                    // The worker wrote the sub-agent's final response into the SubagentRegistry.
                    SubagentRegistry.SubagentState st = subagentRegistry.get(userId, name).orElse(null);
                    String response = (st != null && st.lastResponse() != null)
                            ? st.lastResponse() : t.output();
                    return ToolResult.success(response, Map.of(
                            "subagent", name, "sessionId", sessionId, "taskId", taskId,
                            "async", false));
                }
                case FAILED -> {
                    return ToolResult.error("Sub-agent '" + name + "' failed: "
                            + (t.error() != null ? t.error() : "unknown error"),
                            Map.of("subagent", name, "sessionId", sessionId,
                                    "taskId", taskId, "async", false));
                }
                case CANCELLED -> {
                    return ToolResult.error("Sub-agent '" + name + "' was cancelled.",
                            Map.of("subagent", name, "sessionId", sessionId,
                                    "taskId", taskId, "async", false));
                }
                default -> {
                    // PENDING / RUNNING — keep polling.
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return ToolResult.error("Sub-agent '" + name + "' wait interrupted.",
                        Map.of("subagent", name, "sessionId", sessionId, "taskId", taskId));
            }
        }
        // Timed out — the sub-agent is still running in the background. The model can
        // fetch the result later with send_message (which has its own bounded wait).
        return ToolResult.success("Sub-agent '" + name + "' is still running after "
                + SYNC_WAIT_SECONDS + "s. Its result is not ready yet; call send_message(name='"
                + name + "', message='status?') to wait for and fetch its result.",
                Map.of("subagent", name, "sessionId", sessionId, "taskId", taskId,
                        "async", true, "stillRunning", true));
    }

    private static boolean optionalBool(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = arguments.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString());
    }

    @PreDestroy
    void shutdownExecutor() {
        // No local executor to shut down anymore — sub-agents run on the distributed
        // TaskManager consumer pool. Kept for backward compatibility with any subclass
        // lifecycle hooks; harmless no-op.
    }
}
