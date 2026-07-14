package uct8086.ai.common.enums;

/**
 * Permission modes for the agent harness.
 * Controls how the agent handles potentially destructive operations.
 */
public enum PermissionMode {
    /** Ask before write/execute - default for daily development */
    DEFAULT,
    /** Allow everything without asking - for sandboxed environments */
    AUTO,
    /** Block all writes - for large refactors, review first */
    PLAN_MODE,
    /** Allow read operations only */
    READ_ONLY
}
