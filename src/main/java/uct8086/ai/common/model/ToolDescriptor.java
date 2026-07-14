package uct8086.ai.common.model;

import uct8086.ai.common.enums.ToolCategory;

/**
 * Describes a tool's metadata for registration and discovery.
 */
public record ToolDescriptor(
        String name,
        String description,
        ToolCategory category,
        boolean isReadOnly
) {
    public ToolDescriptor(String name, String description, ToolCategory category) {
        this(name, description, category, false);
    }
}
