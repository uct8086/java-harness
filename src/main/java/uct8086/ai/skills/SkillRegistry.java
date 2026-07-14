package uct8086.ai.skills;

import uct8086.ai.core.config.HarnessProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final SkillLoader skillLoader;
    private final Path skillsDir;
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    public SkillRegistry(SkillLoader skillLoader, HarnessProperties properties) {
        this.skillLoader = skillLoader;
        this.skillsDir = Path.of(properties.getWorkingDirectory(), ".uct8086", "skills");
    }

    public void loadFromDirectory(Path directory) {
        Map<String, Skill> loaded = skillLoader.loadFromDirectory(directory);
        skills.putAll(loaded);
        log.info("Loaded {} skills from {}", loaded.size(), directory);
    }

    /**
     * Register a skill — in-memory AND persisted to the .uct8086/skills/ directory.
     */
    public void register(Skill skill) {
        skills.put(skill.name(), skill);
        persistSkill(skill);
        log.info("Registered skill: {} -> {}", skill.name(), skillsDir.resolve(skill.name() + ".md"));
    }

    public Optional<Skill> getSkill(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Optional<String> getSkillContent(String name) {
        return getSkill(name).map(Skill::content);
    }

    public List<Skill> listSkills() {
        return skills.values().stream().sorted(Comparator.comparing(Skill::name)).toList();
    }

    public List<String> getAllContents() {
        return skills.values().stream().map(Skill::content).toList();
    }

    public boolean hasSkill(String name) { return skills.containsKey(name); }
    public int size() { return skills.size(); }

    private void persistSkill(Skill skill) {
        try {
            Files.createDirectories(skillsDir);
            // YAML frontmatter format matching SkillLoader.parse()
            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("name: ").append(skill.name()).append("\n");
            sb.append("description: ").append(skill.description()).append("\n");
            if (skill.metadata() != null) {
                skill.metadata().forEach((k, v) -> {
                    if (!"name".equals(k) && !"description".equals(k))
                        sb.append(k).append(": ").append(v).append("\n");
                });
            }
            sb.append("---\n\n");
            sb.append(skill.content());
            Files.writeString(skillsDir.resolve(skill.name() + ".md"), sb.toString());
        } catch (IOException e) {
            log.warn("Failed to persist skill {}: {}", skill.name(), e.getMessage());
        }
    }
}
