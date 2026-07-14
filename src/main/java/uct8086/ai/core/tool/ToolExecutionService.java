package uct8086.ai.core.tool;

import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import java.util.Map;

/**
 * Service for executing tools with permission checks and lifecycle hooks.
 * Maps to OpenHarness's tool execution pipeline.
 *
 * <p>Execution pipeline:
 * <ol>
 *   <li>Permission check</li>
 *   <li>PreToolUse hooks</li>
 *   <li>Tool execution</li>
 *   <li>PostToolUse hooks</li>
 *   <li>Return result</li>
 * </ol>
 */
public interface ToolExecutionService {

    /**
     * Execute a tool by name with full permission and hook pipeline.
     *
     * @param toolName  the name of the tool to execute
     * @param arguments the tool input arguments
     * @param context   the execution context
     * @return the result of the tool execution
     */
    ToolResult execute(String toolName, Map<String, Object> arguments, ToolExecutionContext context);
}
