package uct8086.ai.coordinator;

import jakarta.annotation.PostConstruct;
import uct8086.ai.common.enums.AgentRole;
import uct8086.ai.core.engine.AgentEngine;
import uct8086.ai.core.engine.AgentLoopResult;
import uct8086.ai.tasks.TaskManager;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Coordinator for multi-agent operations over the distributed task queue.
 * Maps to OpenHarness's swarm backend (subprocess mode): subtasks submitted
 * here are executed by a TaskManager consumer on a worker node.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Execute queued {@code SUBTASK} tasks through {@link AgentEngine} (the
 *       consumer-side counterpart of {@link uct8086.ai.core.tool.AgentTool}'s
 *       producer-side enqueue).</li>
 *   <li>Write the sub-agent's final response back into the distributed
 *       {@link SubagentRegistry} so any instance can later read it via
 *       {@code send_message}.</li>
 * </ul>
 *
 * <p>This is the single, unified orchestration path. {@link uct8086.ai.core.tool.AgentTool}
 * enqueues a SUBTASK message via {@link TaskManager#createTask}; one of the worker
 * instances in the {@code harness-task-consumers} group picks it up and runs this
 * handler, which calls {@link AgentEngine#execute(Long, String, String, String,
 * uct8086.ai.coordinator.AgentScope) AgentEngine.execute(..., AgentScope.SUBAGENT)}.
 * The orchestration mode ({@link uct8086.ai.coordinator.OrchestrationMode}) is
 * applied by the engine — e.g. COORDINATOR hides the orchestration primitives from
 * the sub-agent — so no topology-aware code is needed here.
 *
 * <p>Replaces the previous in-process {@code AgentCoordinator.spawnSubagent} entry
 * point, which was never actually wired to the LLM-driven {@code agent} tool and
 * existed only as a partial migration stub. With {@code AgentTool} now dispatching
 * directly through {@code TaskManager}, the producer side is also distributed,
 * and {@code spawnSubagent} is no longer needed (kept as a thin delegate for
 * backward compatibility / direct programmatic use).
 */
@Component
public class AgentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentCoordinator.class);

    private final TaskManager taskManager;
    private final SubagentRegistry subagentRegistry;
    // Lazy lookup avoids a constructor-time dependency cycle between the coordinator
    // (used by AgentEngine for delegation) and AgentEngine (used here to run subtasks).
    private final ObjectProvider<AgentEngine> agentEngineProvider;

    public AgentCoordinator(TaskManager taskManager,
                            SubagentRegistry subagentRegistry,
                            ObjectProvider<AgentEngine> agentEngineProvider) {
        this.taskManager = taskManager;
        this.subagentRegistry = subagentRegistry;
        this.agentEngineProvider = agentEngineProvider;
    }

    /**
     * Register the {@code SUBTASK} task handler with the distributed TaskManager.
     *
     * <p>The handler runs the delegated subtask through the {@link AgentEngine} with
     * the subagent's role system prompt, in an isolated session so the subagent's
     * conversation does not pollute the orchestrator's history. It then records the
     * sub-agent's final response into {@link SubagentRegistry} so that
     * {@code send_message} issued from any instance can fetch it.
     */
    @PostConstruct
    public void registerTaskHandlers() {
        taskManager.registerHandler("SUBTASK", fields -> {
            String name = fields.get("name");
            String task = fields.get("task");
            String systemPrompt = fields.get("role");
            String sessionId = fields.get("sessionId");
            Long userId = parseUserId(fields.get("userId"));
            log.info("Subagent '{}' executing task (userId={}, sessionId={})",
                    name, userId, sessionId);

            AgentEngine engine = agentEngineProvider.getIfAvailable();
            if (engine == null) {
                String msg = "Subagent execution unavailable: AgentEngine not initialized";
                recordSubagentFailure(userId, name, msg);
                return msg;
            }
            if (userId == null) {
                String msg = "Subagent execution failed: missing userId";
                recordSubagentFailure(userId, name, msg);
                return msg;
            }

            // Look up the pre-registered sub-agent entry (created by AgentTool when it
            // enqueued this task). We pass its role/systemPrompt through to the engine
            // and reuse its sessionId if the payload included one (the orchestrator
            // creates the session up front to keep send_message resumable across instances).
            String role = systemPrompt;
            SubagentRegistry.SubagentState state =
                    subagentRegistry.get(userId, name).orElse(null);
            if (state != null) {
                if (role == null || role.isBlank()) {
                    role = state.role();
                }
                if (sessionId == null || sessionId.isBlank()) {
                    sessionId = state.sessionId();
                }
            }

            try {
                // The subagent runs in its own session (reused if the orchestrator
                // pre-created one), with its role as additional context. The SUBAGENT
                // scope makes the configured orchestration mode apply (e.g. COORDINATOR
                // hides the orchestration primitives from it).
                AgentLoopResult result = engine.execute(
                        userId, task, sessionId, role, AgentScope.SUBAGENT);

                // If the engine materialized a new sessionId (payload didn't carry one
                // and the engine created a session), push it back into the registry so
                // send_message can resume that session from any instance.
                if (state != null) {
                    // state.sessionId() might differ from what the engine actually used
                    // if sessionId was null above and the engine created a fresh session.
                    // We don't have direct access to the engine's resolved session here,
                    // but for the common path the engine reused the provided sessionId,
                    // so no update is needed. If the caller passed sessionId=null and
                    // expects resume, they should rely on AgentTool's pre-creation path.
                    if (result.success()) {
                        subagentRegistry.markCompleted(state, result.response());
                        log.info("Subagent '{}' completed ({} turns)", name, result.turns());
                        return result.response();
                    } else {
                        subagentRegistry.markFailed(state, result.error());
                        log.warn("Subagent '{}' failed: {}", name, result.error());
                        return "Subagent execution failed: " + result.error();
                    }
                } else {
                    // The sub-agent was not pre-registered (legacy / direct-programmatic
                    // path). Just return the engine's response; the orchestrator is
                    // responsible for tracking state in this case.
                    if (result.success()) {
                        log.info("Subagent '{}' completed ({} turns, unregistered)", name, result.turns());
                        return result.response();
                    }
                    log.warn("Subagent '{}' failed (unregistered): {}", name, result.error());
                    return "Subagent execution failed: " + result.error();
                }
            } catch (Exception e) {
                String msg = "Subagent '" + name + "' threw: " + e.getMessage();
                log.error("Subagent '{}' threw an exception", name, e);
                if (state != null) {
                    subagentRegistry.markFailed(state, msg);
                }
                return msg;
            }
        });
    }

    private void recordSubagentFailure(Long userId, String name, String error) {
        if (userId == null || name == null) return;
        subagentRegistry.get(userId, name).ifPresent(s -> subagentRegistry.markFailed(s, error));
    }

    private static Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Spawn a subagent for a specific task (asynchronous, distributed queue).
     *
     * <p>Kept for backward compatibility with any direct programmatic caller. The
     * LLM-driven path uses {@link uct8086.ai.core.tool.AgentTool} directly, which is
     * now wired to the same Redis Stream via {@link TaskManager}.
     *
     * @param name         the agent name
     * @param systemPrompt the system prompt / role for the subagent
     * @param task         the task to execute
     * @return the created subagent descriptor
     */
    public Subagent spawnSubagent(Long userId, String name, String systemPrompt, String task) {
        Subagent subagent = new Subagent(name, AgentRole.SUBAGENT, systemPrompt).withStatus("running");

        taskManager.createTask(userId, name, "Subagent task: " + task,
                "SUBTASK",
                Map.of("name", name, "role", systemPrompt, "task", task));

        log.info("Spawned subagent: {} ({})", name, subagent.id());
        return subagent;
    }
}
