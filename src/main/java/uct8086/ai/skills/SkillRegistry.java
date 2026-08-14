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
    private final Path skillsRoot;

    // System-wide skills loaded from the project directory at startup (shared).
    private final Map<String, Skill> systemSkills = new ConcurrentHashMap<>();
    // Per-user skills (user id -> skill name -> skill).
    private final Map<Long, Map<String, Skill>> userSkills = new ConcurrentHashMap<>();

    public SkillRegistry(SkillLoader skillLoader, HarnessProperties properties) {
        this.skillLoader = skillLoader;
        this.skillsRoot = Path.of(properties.getWorkingDirectory(), ".uct8086", "skills");
    }

    /**
     * Load system-wide skills from a directory (startup, shared across users).
     */
    public void loadFromDirectory(Path directory) {
        Map<String, Skill> loaded = skillLoader.loadFromDirectory(directory);
        systemSkills.putAll(loaded);
        log.info("Loaded {} system skills from {}", loaded.size(), directory);
    }

    /**
     * Register a user-owned skill — in-memory AND persisted under the user's directory.
     */
    public void register(Long userId, Skill skill) {
        userSkills.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(skill.name(), skill);
        persistSkill(userId, skill);
        log.info("Registered skill for user {}: {} -> {}", userId, skill.name(), userSkillFile(userId, skill.name()));
    }

    public Optional<Skill> getSkill(Long userId, String name) {
        Map<String, Skill> map = userSkills.get(userId);
        return map != null ? Optional.ofNullable(map.get(name)) : Optional.empty();
    }

    public Optional<String> getSkillContent(Long userId, String name) {
        return getSkill(userId, name).map(Skill::content);
    }

    /**
     * List user-owned skills for the given user.
     */
    public List<Skill> listSkills(Long userId) {
        Map<String, Skill> map = userSkills.get(userId);
        if (map == null) {
            return List.of();
        }
        return map.values().stream().sorted(Comparator.comparing(Skill::name)).toList();
    }

    /**
     * List system-wide skills (shared).
     */
    public List<Skill> listSystemSkills() {
        return systemSkills.values().stream().sorted(Comparator.comparing(Skill::name)).toList();
    }

    /**
     * Number of system skills.
     */
    public int size() {
        return systemSkills.size();
    }

    public boolean hasSkill(Long userId, String name) {
        Map<String, Skill> map = userSkills.get(userId);
        return map != null && map.containsKey(name);
    }

    public List<String> getAllContents() {
        return systemSkills.values().stream().map(Skill::content).toList();
    }

    private Path userSkillFile(Long userId, String name) {
        return skillsRoot.resolve(String.valueOf(userId)).resolve(name + ".md");
    }

    private void persistSkill(Long userId, Skill skill) {
        synchronized (this) {
            try {
                Path dir = skillsRoot.resolve(String.valueOf(userId));
                Files.createDirectories(dir);
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
                Files.writeString(dir.resolve(skill.name() + ".md"), sb.toString());
            } catch (IOException e) {
                log.warn("Failed to persist skill {} for user {}: {}", skill.name(), userId, e.getMessage());
            }
        }
    }
}
