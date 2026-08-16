package uct8086.ai.coordinator;

/**
 * Which side of the orchestration topology an agent execution belongs to.
 *
 * <ul>
 *   <li>{@link #MAIN} — the user-facing agent loop (chat entry points).</li>
 *   <li>{@link #SUBAGENT} — a run spawned by an orchestrator: via the 'agent'
 *       tool, a send_message continuation, or the distributed SUBTASK queue.</li>
 * </ul>
 *
 * <p>Combined with {@link OrchestrationMode} this decides whether the
 * orchestration primitives are visible in that run (see
 * {@link OrchestrationMode#excludesOrchestrationTools(boolean)}).
 */
public enum AgentScope {
    MAIN,
    SUBAGENT
}
