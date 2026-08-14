package uct8086.ai.skills;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import uct8086.ai.core.config.HarnessProperties;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registry for skills.
 *
 * <p>Two distinct skill scopes:
 * <ul>
 *   <li><b>System skills</b> — bundled with the application, loaded from the project
 *       directory at startup and shared across all users. These are code assets and
 *       remain filesystem-backed (not in MySQL).</li>
 *   <li><b>User skills</b> — created per-user at runtime, persisted in MySQL
 *       ({@code harness_skill} table) so they survive restarts and are shared across
 *       horizontally-scaled instances.</li>
 * </ul>
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final SkillLoader skillLoader;
    private final SkillMapper skillMapper;
    private final ObjectMapper objectMapper;

    // System-wide skills loaded from the project directory at startup (shared).
    private final Map<String, Skill> systemSkills = new ConcurrentHashMap<>();

    public SkillRegistry(SkillLoader skillLoader,
                         SkillMapper skillMapper,
                         ObjectMapper objectMapper,
                         HarnessProperties properties) {
        this.skillLoader = skillLoader;
        this.skillMapper = skillMapper;
        this.objectMapper = objectMapper;
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
     * Register a user-owned skill, persisted to MySQL.
     */
    public void register(Long userId, Skill skill) {
        SkillEntity existing = findByUserAndName(userId, skill.name());
        SkillEntity entity = toEntity(userId, skill);
        if (existing != null) {
            entity.setId(existing.getId());
            entity.setCreatedAt(existing.getCreatedAt());
            skillMapper.updateById(entity);
        } else {
            skillMapper.insert(entity);
        }
        log.info("Registered skill for user {}: {}", userId, skill.name());
    }

    public Optional<Skill> getSkill(Long userId, String name) {
        SkillEntity entity = findByUserAndName(userId, name);
        return entity != null ? Optional.of(toSkill(entity)) : Optional.empty();
    }

    public Optional<String> getSkillContent(Long userId, String name) {
        return getSkill(userId, name).map(Skill::content);
    }

    /**
     * List user-owned skills for the given user (from MySQL).
     */
    public List<Skill> listSkills(Long userId) {
        return skillMapper.findByUserIdOrderByNameAsc(userId)
                .stream().map(this::toSkill).toList();
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
        return findByUserAndName(userId, name) != null;
    }

    public List<String> getAllContents() {
        return systemSkills.values().stream().map(Skill::content).toList();
    }

    // ========== Persistence helpers ==========

    private SkillEntity findByUserAndName(Long userId, String name) {
        return skillMapper.selectOne(
                Wrappers.<SkillEntity>lambdaQuery()
                        .eq(SkillEntity::getUserId, userId)
                        .eq(SkillEntity::getName, name));
    }

    private SkillEntity toEntity(Long userId, Skill skill) {
        SkillEntity e = new SkillEntity();
        e.setUserId(userId);
        e.setName(skill.name());
        e.setDescription(skill.description());
        e.setContent(skill.content());
        e.setMetadataJson(serializeMetadata(skill.metadata()));
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    private Skill toSkill(SkillEntity entity) {
        return new Skill(
                entity.getName(),
                entity.getDescription(),
                entity.getContent(),
                null,
                deserializeMetadata(entity.getMetadataJson())
        );
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Failed to serialize skill metadata", e);
            return null;
        }
    }

    private Map<String, String> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize skill metadata", e);
            return Map.of();
        }
    }
}
