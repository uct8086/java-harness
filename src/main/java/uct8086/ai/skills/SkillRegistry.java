package uct8086.ai.skills;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registry for loaded skills.
 * Maps to OpenHarness's Skill Registry.
 *
 * <p>Supports loading skills from multiple directories:
 * <ul>
 *   <li>Bundled skills (classpath)</li>
 *   <li>Project skills (.uct8086/skills/)</li>
 * </ul>
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final SkillLoader skillLoader;
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    public SkillRegistry(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    /**
     * Load skills from a directory.
     */
    public void loadFromDirectory(Path directory) {
        Map<String, Skill> loaded = skillLoader.loadFromDirectory(directory);
        skills.putAll(loaded);
        log.info("Loaded {} skills from {}", loaded.size(), directory);
    }

    /**
     * Register a skill manually.
     */
    public void register(Skill skill) {
        skills.put(skill.name(), skill);
        log.debug("Registered skill: {}", skill.name());
    }

    /**
     * Get a skill by name.
     */
    public Optional<Skill> getSkill(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * Get the content of a skill by name.
     */
    public Optional<String> getSkillContent(String name) {
        return getSkill(name).map(Skill::content);
    }

    /**
     * List all skill names with descriptions.
     */
    public List<Skill> listSkills() {
        return skills.values().stream()
                .sorted(Comparator.comparing(Skill::name))
                .toList();
    }

    /**
     * Get all skill contents for prompt injection.
     */
    public List<String> getAllContents() {
        return skills.values().stream()
                .map(Skill::content)
                .toList();
    }

    /**
     * Check if a skill exists.
     */
    public boolean hasSkill(String name) {
        return skills.containsKey(name);
    }

    /**
     * Get the count of loaded skills.
     */
    public int size() {
        return skills.size();
    }
}
