package uct8086.ai.core.tool;

import uct8086.ai.common.model.ToolDescriptor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uct8086.ai.common.enums.ToolCategory;

/**
 * Registry for all available tools in the harness.
 * Maps to OpenHarness's Tool Registry.
 *
 * <p>Tools are registered at startup and can be discovered by name or category.
 * The registry supports dynamic registration for plugins and MCP tools.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, HarnessTool> tools = new ConcurrentHashMap<>();

    /**
     * Register a tool.
     */
    public void register(HarnessTool tool) {
        Objects.requireNonNull(tool, "Tool cannot be null");
        String name = tool.getName();
        if (tools.containsKey(name)) {
            log.warn("Overwriting existing tool: {}", name);
        }
        tools.put(name, tool);
        log.debug("Registered tool: {} ({})", name, tool.getCategory());
    }

    /**
     * Unregister a tool by name.
     */
    public void unregister(String name) {
        HarnessTool removed = tools.remove(name);
        if (removed != null) {
            log.debug("Unregistered tool: {}", name);
        }
    }

    /**
     * Get a tool by name.
     */
    public Optional<HarnessTool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Check if a tool exists.
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * List all registered tool descriptors.
     */
    public List<ToolDescriptor> listTools() {
        return tools.values().stream()
                .map(HarnessTool::toDescriptor)
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    /**
     * List all tool descriptors for a given category.
     */
    public List<ToolDescriptor> listTools(ToolCategory category) {
        return tools.values().stream()
                .filter(t -> t.getCategory() == category)
                .map(HarnessTool::toDescriptor)
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    /**
     * Get all registered tool names.
     */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * Get the count of registered tools.
     */
    public int size() {
        return tools.size();
    }

    /**
     * Get all registered tools.
     */
    public Collection<HarnessTool> getAll() {
        return Collections.unmodifiableCollection(tools.values());
    }
}
