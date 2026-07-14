package uct8086.ai.core.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import uct8086.ai.core.tool.HarnessTool;
import uct8086.ai.core.tool.ToolExecutionService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import uct8086.ai.common.enums.PermissionMode;

/**
 * Adapts a {@link HarnessTool} to Spring AI's {@link ToolCallback} interface.
 * Routes tool execution through {@link ToolExecutionService} for permission + hooks.
 *
 * <p>Uses a ThreadLocal to pass the execution context since the ToolCallback
 * interface does not support context parameters directly.
 */
public class HarnessToolCallbackAdapter implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(HarnessToolCallbackAdapter.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final ThreadLocal<ToolExecutionContext> contextHolder = new ThreadLocal<>();

    private final HarnessTool tool;
    private final ToolExecutionService executionService;
    private final ToolDefinition toolDefinition;

    public HarnessToolCallbackAdapter(HarnessTool tool, ToolExecutionService executionService) {
        this.tool = tool;
        this.executionService = executionService;
        this.toolDefinition = ToolDefinition.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .inputSchema(generateInputSchema())
                .build();
    }

    /**
     * Set the execution context for the current thread.
     */
    public static void setContext(ToolExecutionContext context) {
        contextHolder.set(context);
    }

    /**
     * Clear the execution context for the current thread.
     */
    public static void clearContext() {
        contextHolder.remove();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder()
                .returnDirect(false)
                .build();
    }

    @Override
    public String call(String toolInput) {
        log.debug("Tool callback invoked: {} input={}", tool.getName(), toolInput);

        ToolExecutionContext context = contextHolder.get();
        if (context == null) {
            context = new ToolExecutionContext("default", null,
                    PermissionMode.AUTO, Map.of());
            log.warn("No execution context set for tool '{}', using default", tool.getName());
        }

        Map<String, Object> arguments;
        try {
            arguments = parseArguments(toolInput);
        } catch (Exception e) {
            log.error("Failed to parse tool arguments: {}", toolInput, e);
            return "Error: Invalid tool arguments - " + e.getMessage();
        }

        try {
            ToolResult result = executionService.execute(tool.getName(), arguments, context);
            return result.output();
        } catch (Exception e) {
            log.error("Tool execution failed: {}", tool.getName(), e);
            return "Error: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String toolInput) throws JsonProcessingException {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(toolInput, Map.class);
    }

    /**
     * Generate a JSON schema for the tool input.
     * Uses a permissive schema that accepts any properties.
     */
    private String generateInputSchema() {
        return """
            {
                "type": "object",
                "properties": {},
                "additionalProperties": true
            }""";
    }
}
