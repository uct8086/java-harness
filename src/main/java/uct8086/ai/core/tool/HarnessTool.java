package uct8086.ai.core.tool;

import uct8086.ai.common.enums.ToolCategory;
import uct8086.ai.common.model.ToolDescriptor;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import java.util.Map;

/**
 * Core tool interface for the agent harness.
 * Maps to OpenHarness's BaseTool pattern.
 *
 * <p>Every tool has:
 * <ul>
 *   <li>A unique name for identification</li>
 *   <li>A description for the model to understand when to use it</li>
 *   <li>A category for grouping</li>
 *   <li>Permission integration (checked before every execution)</li>
 *   <li>Hook support (PreToolUse/PostToolUse lifecycle events)</li>
 * </ul>
 */
public interface HarnessTool {

    /**
     * Unique tool name used by the model to call the tool.
     */
    String getName();

    /**
     * Description for the tool, used by the model to understand when and how to use it.
     */
    String getDescription();

    /**
     * Category of the tool.
     */
    ToolCategory getCategory();

    /**
     * Whether this tool only reads data (no side effects).
     * Read-only tools may bypass permission checks in some modes.
     */
    default boolean isReadOnly() {
        return false;
    }

    /**
     * Get the tool descriptor for registration and discovery.
     */
    default ToolDescriptor toDescriptor() {
        return new ToolDescriptor(getName(), getDescription(), getCategory(), isReadOnly());
    }

    /**
     * Execute the tool with the given arguments and context.
     *
     * @param arguments the tool input arguments as a map
     * @param context   the execution context (session, working dir, permissions)
     * @return the result of the tool execution
     */
    ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context);
}
