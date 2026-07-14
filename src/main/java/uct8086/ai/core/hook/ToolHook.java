package uct8086.ai.core.hook;

import uct8086.ai.common.model.HookContext;
import uct8086.ai.common.model.HookDefinition;
import uct8086.ai.common.model.HookResult;

/**
 * Interface for tool lifecycle hooks.
 * Maps to OpenHarness's PreToolUse/PostToolUse hooks.
 *
 * <p>Hooks fire at specific phases of the tool execution lifecycle:
 * <ul>
 *   <li>PRE_TOOL_USE - Before a tool is executed (can block or modify)</li>
 *   <li>POST_TOOL_USE - After a tool is executed (can modify the result)</li>
 * </ul>
 */
public interface ToolHook {

    /**
     * Get the hook definition (name, phase, tool patterns, priority).
     */
    HookDefinition getDefinition();

    /**
     * Process the hook event.
     *
     * @param context the hook context (phase, tool name, arguments, result)
     * @return the hook result (continue, block, or modify)
     */
    HookResult onEvent(HookContext context);
}
