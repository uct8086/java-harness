package uct8086.ai.core.tool;

import uct8086.ai.common.enums.ToolCategory;
import uct8086.ai.common.exception.ToolExecutionException;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import java.util.Map;

/**
 * Abstract base class for tools, providing common functionality.
 * Subclasses implement {@link #doExecute(Map, ToolExecutionContext)}.
 */
public abstract class AbstractTool implements HarnessTool {

    protected final String name;
    protected final String description;
    protected final ToolCategory category;
    protected final boolean readOnly;

    protected AbstractTool(String name, String description, ToolCategory category) {
        this(name, description, category, false);
    }

    protected AbstractTool(String name, String description, ToolCategory category, boolean readOnly) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.readOnly = readOnly;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public ToolCategory getCategory() {
        return category;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        try {
            return doExecute(arguments, context);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException(name, e.getMessage(), e);
        }
    }

    /**
     * Subclasses implement the actual tool logic here.
     */
    protected abstract ToolResult doExecute(Map<String, Object> arguments, ToolExecutionContext context) throws Exception;

    /**
     * Helper to get a required string argument.
     */
    protected String requireString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new ToolExecutionException(name, "Missing required argument: " + key);
        }
        return value.toString();
    }

    /**
     * Helper to get an optional string argument with a default.
     */
    protected String optionalString(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Helper to get a required integer argument.
     */
    protected int requireInt(Map<String, Object> arguments, String key) {
        String value = requireString(arguments, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ToolExecutionException(name, "Invalid integer for argument '" + key + "': " + value);
        }
    }

    /**
     * Helper to get an optional integer argument with a default.
     */
    protected int optionalInt(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
