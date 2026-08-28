package uct8086.ai.core.tool;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import uct8086.ai.common.enums.ToolCategory;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import uct8086.ai.coordinator.SubagentRegistry;
import uct8086.ai.core.session.SessionManager;
import uct8086.ai.tasks.TaskManager;

/**
 * Stop a sub-agent spawned with the {@code agent} tool. Maps to OpenHarness's
 * coordinator {@code task_stop} primitive: the orchestrator can abort a
 * background sub-agent that went off track or is no longer needed, freeing
 * its name for re-use.
 *
 * <h2>Distributed cancellation</h2>
 * <p>Previously this tool cancelled the sub-agent by calling
 * {@code future.cancel(true)} on a local in-memory {@code Future} handle. That
 * only worked on the JVM that spawned the sub-agent: on any other instance the
 * future didn't exist, so the cancel was a no-op even though the sub-agent
 * kept running on a worker.
 *
 * <p>Now cancellation goes through {@link TaskManager#cancelTask}, which sets a
 * Redis cancel flag ({@code harness:task:cancel:{taskId}}) that the executing
 * worker observes and acts on. This works across instances — the worker that
 * picks up the message doesn't need to be the same instance that received the
 * stop request.
 *
 * <h2>Arguments</h2>
 * <ul>
 *   <li>{@code name} (required) — the sub-agent name given to the {@code agent} tool</li>
 * </ul>
 */
@Component
public class TaskStopTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(TaskStopTool.class);

    private final SubagentRegistry subagentRegistry;
    private final SessionManager sessionManager;
    private final TaskManager taskManager;

    public TaskStopTool(SubagentRegistry subagentRegistry,
                       SessionManager sessionManager,
                       TaskManager taskManager) {
        super("task_stop",
                "Stop a sub-agent you spawned with the agent tool. Use it when a background "
                        + "sub-agent went off track, is stuck, or its result is no longer needed. "
                        + "Stopping frees the name, so you can re-spawn a fresh sub-agent under "
                        + "the same name with the agent tool.",
                ToolCategory.AGENT);
        this.subagentRegistry = subagentRegistry;
        this.sessionManager = sessionManager;
        this.taskManager = taskManager;
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> arguments, ToolExecutionContext context) throws Exception {
        String name = requireString(arguments, "name");

        Long userId = sessionManager.resolveUserId(context.sessionId()).orElse(null);
        if (userId == null) {
            return ToolResult.error("Cannot stop sub-agent: unable to resolve user for session "
                    + context.sessionId());
        }

        SubagentRegistry.SubagentState state = subagentRegistry.get(userId, name).orElse(null);
        if (state == null) {
            return ToolResult.error("No sub-agent named '" + name
                    + "'. Spawn one first with the agent tool.");
        }

        if (state.status() == SubagentRegistry.Status.RUNNING) {
            // Mark terminal in the registry first: any late result the worker reports
            // after this will be discarded (STOPPED wins over COMPLETED/FAILED).
            subagentRegistry.markStopped(state);

            // Cross-instance cancel: the worker polls the cancel flag and aborts.
            if (state.taskId() != null && !state.taskId().isBlank()) {
                boolean cancelled = taskManager.cancelTask(userId, state.taskId());
                log.info("Orchestrator stopped sub-agent '{}' (userId={}, taskId={}, redisCancel={})",
                        name, userId, state.taskId(), cancelled);
            } else {
                log.info("Orchestrator stopped sub-agent '{}' (userId={}, no taskId; registry-only stop)",
                        name, userId);
            }
            return ToolResult.success("Sub-agent '" + name + "' stopped. The name can now be "
                    + "re-used by spawning a new sub-agent with the agent tool.");
        }
        return ToolResult.success("Sub-agent '" + name + "' is not running (status: "
                + state.status() + "). No action taken.");
    }
}
