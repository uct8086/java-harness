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
 *   <li>Spawn subagents for task delegation through the Redis Stream queue</li>
 *   <li>Execute queued {@code SUBTASK} tasks through {@link AgentEngine}</li>
 * </ul>
 *
 * <p>In-process orchestration (the LLM driving sub-agents via tool calls) is
 * implemented by the orchestration tools 'agent' / 'send_message' / 'task_stop'
 * in {@code uct8086.ai.core.tool}; this coordinator covers the distributed path.
 */
@Component
public class AgentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentCoordinator.class);

    private final TaskManager taskManager;
    // Lazy lookup avoids a constructor-time dependency cycle between the coordinator
    // (used by AgentEngine for delegation) and AgentEngine (used here to run subtasks).
    private final ObjectProvider<AgentEngine> agentEngineProvider;

    public AgentCoordinator(TaskManager taskManager,
                            ObjectProvider<AgentEngine> agentEngineProvider) {
        this.taskManager = taskManager;
        this.agentEngineProvider = agentEngineProvider;
    }

    /**
     * Register the {@code SUBTASK} task handler with the distributed TaskManager.
     *
     * <p>The handler runs the delegated subtask through the {@link AgentEngine} with
     * the subagent's role system prompt, in an isolated session so the subagent's
     * conversation does not pollute the orchestrator's history.
     */
    @PostConstruct
    public void registerTaskHandlers() {
        taskManager.registerHandler("SUBTASK", fields -> {
            String name = fields.get("name");
            String task = fields.get("task");
            String systemPrompt = fields.get("systemPrompt");
            Long userId = parseUserId(fields.get("userId"));
            log.info("Subagent '{}' executing task: {}", name, task);

            AgentEngine engine = agentEngineProvider.getIfAvailable();
            if (engine == null) {
                return "Subagent execution unavailable: AgentEngine not initialized";
            }
            if (userId == null) {
                return "Subagent execution failed: missing userId";
            }

            // The subagent runs in its own session (sessionId == null → new session),
            // with its role system prompt injected as additional context. The SUBAGENT
            // scope makes the configured orchestration mode apply (e.g. COORDINATOR
            // hides the orchestration primitives from it).
            AgentLoopResult result = engine.execute(userId, task, null, systemPrompt, AgentScope.SUBAGENT);
            if (result.success()) {
                log.info("Subagent '{}' completed task ({} turns)", name, result.turns());
                return result.response();
            }
            log.warn("Subagent '{}' failed: {}", name, result.error());
            return "Subagent execution failed: " + result.error();
        });
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
     * @param name         the agent name
     * @param systemPrompt the system prompt for the subagent
     * @param task         the task to execute
     * @return the created subagent
     */
    public Subagent spawnSubagent(Long userId, String name, String systemPrompt, String task) {
        Subagent subagent = new Subagent(name, AgentRole.SUBAGENT, systemPrompt).withStatus("running");

        // Enqueue a distributed task for the subagent (executed by a TaskManager
        // consumer). The task body is a serializable definition, not a Callable.
        taskManager.createTask(userId, name, "Subagent task: " + task,
                "SUBTASK",
                Map.of("name", name, "systemPrompt", systemPrompt, "task", task));

        log.info("Spawned subagent: {} ({})", name, subagent.id());
        return subagent;
    }
}
