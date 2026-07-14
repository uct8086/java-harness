package uct8086.ai.common.enums;

/**
 * Role of an agent in the multi-agent coordination system.
 */
public enum AgentRole {
    /** Primary agent handling user interaction */
    MAIN,
    /** Subagent spawned for delegation */
    SUBAGENT,
    /** Teammate agent in a team */
    TEAMMATE
}
