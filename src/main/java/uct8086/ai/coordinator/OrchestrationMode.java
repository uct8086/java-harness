package uct8086.ai.coordinator;

import java.util.Set;

/**
 * Multi-agent orchestration topology mode. Maps to OpenHarness's Coordinator
 * Mode: it defines <em>who holds the orchestration decision rights</em>, i.e.
 * which agents get the orchestration primitives ('agent' / 'send_message' /
 * 'task_stop').
 *
 * <ul>
 *   <li>{@link #LOCAL} — no orchestration at all: the orchestration tools are
 *       not registered, every conversation is a single agent (OpenHarness LOCAL).</li>
 *   <li>{@link #COORDINATOR} — star topology: only the main agent holds the
 *       orchestration tools; sub-agents execute their task but cannot spawn
 *       further agents (OpenHarness COORDINATOR).</li>
 *   <li>{@link #SWARM} — recursive topology: sub-agents also receive the
 *       orchestration tools and may spawn their own sub-agents (OpenHarness SWARM).</li>
 * </ul>
 */
public enum OrchestrationMode {

    LOCAL,
    COORDINATOR,
    SWARM;

    /** Names of the three orchestration primitives subject to mode filtering. */
    public static final Set<String> ORCHESTRATION_TOOLS =
            Set.of("agent", "send_message", "task_stop");

    /**
     * Whether the orchestration primitives must be hidden in the given context.
     *
     * @param subagent true if the tools are being prepared for a sub-agent run
     *                 (spawned via the 'agent' tool, send_message continuation,
     *                 or the distributed SUBTASK queue)
     */
    public boolean excludesOrchestrationTools(boolean subagent) {
        return switch (this) {
            case LOCAL -> true;        // hidden from everyone
            case COORDINATOR -> subagent; // hidden from sub-agents (star topology)
            case SWARM -> false;       // visible to everyone
        };
    }
}
