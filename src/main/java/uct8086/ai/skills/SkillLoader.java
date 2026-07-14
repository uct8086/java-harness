package uct8086.ai.skills;

import uct8086.ai.common.exception.SkillLoadException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads skill files from the filesystem.
 * Maps to OpenHarness's skill loading from multiple locations:
 * <ul>
 *   <li>Bundled skills</li>
 *   <li>Project skills (.uct8086/skills/)</li>
 *   <li>Plugin skills</li>
 * </ul>
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    /**
     * Load a skill from a markdown file.
     * Parses YAML frontmatter and extracts name, description, and content.
     *
     * @param skillFile the path to the SKILL.md file
     * @return the loaded skill
     * @throws SkillLoadException if the file cannot be loaded or parsed
     */
    public Skill load(Path skillFile) throws SkillLoadException {
        String fileName = skillFile.getFileName() != null
                ? skillFile.getFileName().toString()
                : skillFile.toString();

        try {
            String content = Files.readString(skillFile);
            return parse(fileName, content, skillFile);
        } catch (IOException e) {
            throw new SkillLoadException(fileName, "Cannot read file: " + e.getMessage(), e);
        }
    }

    /**
     * Parse skill content with YAML frontmatter.
     */
    public Skill parse(String name, String content, Path sourcePath) throws SkillLoadException {
        String skillName = name;
        String description = "";
        Map<String, String> metadata = new HashMap<>();
        String body = content;

        // Check for YAML frontmatter
        if (content.startsWith("---")) {
            int endIndex = content.indexOf("---", 3);
            if (endIndex > 0) {
                String frontmatter = content.substring(3, endIndex).trim();
                body = content.substring(endIndex + 3).trim();

                // Parse simple YAML key-value pairs
                for (String line : frontmatter.split("\n")) {
                    line = line.trim();
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(0, colon).trim();
                        String value = line.substring(colon + 1).trim();
                        metadata.put(key, value);

                        if ("name".equals(key)) {
                            skillName = value;
                        } else if ("description".equals(key)) {
                            description = value;
                        }
                    }
                }
            }
        }

        log.debug("Loaded skill: {} from {}", skillName, sourcePath);
        return new Skill(skillName, description, body, sourcePath, Map.copyOf(metadata));
    }

    /**
     * Load all skills from a directory.
     *
     * @param skillsDir the directory containing skill .md files
     * @return a map of skill name to Skill
     */
    public Map<String, Skill> loadFromDirectory(Path skillsDir) {
        Map<String, Skill> skills = new HashMap<>();

        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            log.debug("Skills directory not found: {}", skillsDir);
            return skills;
        }

        try (var paths = Files.list(skillsDir)) {
            paths.filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> {
                        try {
                            Skill skill = load(p);
                            skills.put(skill.name(), skill);
                        } catch (SkillLoadException e) {
                            log.warn("Failed to load skill from {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to list skills directory: {}", skillsDir, e);
        }

        return skills;
    }
}
