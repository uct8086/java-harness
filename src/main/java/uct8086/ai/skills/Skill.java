package uct8086.ai.skills;

import java.nio.file.Path;
import java.util.Map;

/**
 * Represents a loaded skill.
 * Maps to OpenHarness's Skills System (on-demand .md knowledge loading).
 *
 * <p>Skills are markdown files with YAML frontmatter:
 * <pre>
 * ---
 * name: my-skill
 * description: Expert guidance for a domain
 * ---
 * # Skill content...
 * </pre>
 */
public record Skill(
        String name,
        String description,
        String content,
        Path sourcePath,
        Map<String, String> metadata
) {
    public Skill(String name, String description, String content, Path sourcePath) {
        this(name, description, content, sourcePath, Map.of());
    }
}
