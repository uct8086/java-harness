package uct8086.ai.core.permission;

import uct8086.ai.common.enums.PermissionMode;
import uct8086.ai.common.model.PermissionResult;
import uct8086.ai.common.model.ToolExecutionContext;
import java.util.Map;

/**
 * Permission checker interface for the agent harness.
 * Maps to OpenHarness's PermissionChecker.
 *
 * <p>Controls whether a tool can be executed based on:
 * <ul>
 *   <li>Permission mode (DEFAULT, AUTO, PLAN_MODE, READ_ONLY)</li>
 *   <li>Path-level rules (allow/dy patterns)</li>
 *   <li>Denied commands list</li>
 *   <li>Tool category and read-only status</li>
 * </ul>
 */
public interface PermissionChecker {

    /**
     * Check if a tool can be executed.
     *
     * @param toolName  the name of the tool to check
     * @param arguments the tool arguments (may contain paths or commands)
     * @param context   the execution context
     * @return the permission result
     */
    PermissionResult check(String toolName, Map<String, Object> arguments, ToolExecutionContext context);

    /**
     * Get the current permission mode.
     */
    PermissionMode getMode();

    /**
     * Set the permission mode.
     */
    void setMode(PermissionMode mode);
}
