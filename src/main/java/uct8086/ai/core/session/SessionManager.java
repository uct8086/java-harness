package uct8086.ai.core.session;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import uct8086.ai.common.model.AgentMessage;
import uct8086.ai.common.model.SessionInfo;
import uct8086.ai.persistence.MessageEntity;
import uct8086.ai.persistence.MessageMapper;
import uct8086.ai.persistence.SessionEntity;
import uct8086.ai.persistence.SessionMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Manages conversation sessions, backed by MySQL for persistence and Redis for caching.
 *
 * <p>All operations are scoped to a {@code userId}, so each user only sees and
 * manipulates their own sessions and messages. Redis cache keys are namespaced by
 * user id to prevent cross-user cache leakage.
 *
 * <p>Redis cache keys:
 * <ul>
 *   <li>{@code harness:session:zset:{userId}} -&gt; ZSET of sessionId scored by updatedAt
 *       (ordered index for paginated listing)</li>
 *   <li>{@code harness:session:meta:{userId}:{id}} -&gt; single {@link SessionInfo} JSON (TTL 10m)</li>
 *   <li>{@code harness:session:messages:{userId}:{id}} -&gt; list of {@link AgentMessage} (TTL 10m)</li>
 * </ul>
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private static final String CACHE_SESSION_ZSET_PREFIX = "harness:session:zset:";
    private static final String CACHE_SESSION_META_PREFIX = "harness:session:meta:";
    private static final String CACHE_SESSION_MESSAGES_PREFIX = "harness:session:messages:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration ZSET_TTL = Duration.ofHours(24);
    /** Max number of messages loaded/cached per session. */
    private static final int MAX_MESSAGES_PER_SESSION = 100;

    private static final TypeReference<SessionInfo> SESSION_INFO_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<AgentMessage>> MESSAGE_LIST_TYPE = new TypeReference<>() {};

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionManager(SessionMapper sessionMapper,
                          MessageMapper messageMapper,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new session for the given user.
     */
    public ConversationSession createSession(Long userId, String name) {
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        SessionEntity entity = new SessionEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setName(name != null ? name : "session");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setMessageCount(0);
        sessionMapper.insert(entity);
        updateZsetAndMeta(userId, entity);
        log.info("Created session: {} ({}) for user {}", name, id, userId);
        return toConversationSession(entity);
    }

    /**
     * Create a new unnamed session for the given user.
     */
    public ConversationSession createSession(Long userId) {
        return createSession(userId, "session-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * Get a session by ID, verifying it belongs to the given user.
     */
    public Optional<ConversationSession> getSession(Long userId, String sessionId) {
        SessionEntity entity = sessionMapper.selectById(sessionId);
        if (entity == null || !userId.equals(entity.getUserId())) {
            return Optional.empty();
        }
        return Optional.of(toConversationSession(entity));
    }

    /**
     * Add a message to a session owned by the given user.
     */
    public void addMessage(Long userId, String sessionId, AgentMessage message) {
        SessionEntity entity = sessionMapper.selectById(sessionId);
        if (entity == null || !userId.equals(entity.getUserId())) {
            log.warn("Session not found or not owned by user {}: {}", userId, sessionId);
            return;
        }
        messageMapper.insert(toMessageEntity(userId, sessionId, message));

        entity.setMessageCount(entity.getMessageCount() + 1);
        entity.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(entity);

        updateMessagesCache(userId, sessionId, message);
        updateZsetAndMeta(userId, entity);
        log.debug("Added {} message to session {} (total {})", message.role(), sessionId, entity.getMessageCount());
    }

    /**
     * Get messages from a session owned by the given user (Redis-cached).
     */
    public List<AgentMessage> getMessages(Long userId, String sessionId) {
        String cacheKey = messagesCacheKey(userId, sessionId);
        Optional<List<AgentMessage>> cached = readCache(cacheKey, MESSAGE_LIST_TYPE);
        if (cached.isPresent()) {
            log.info("[SESSION-MSG] userId={} session={} 缓存命中, 返回 {} 条消息", userId, sessionId, cached.get().size());
            return cached.get();
        }
        List<MessageEntity> entities = messageMapper.findRecentByUserIdAndSessionId(userId, sessionId, MAX_MESSAGES_PER_SESSION);
        List<AgentMessage> messages = entities.stream().map(this::toAgentMessage).toList();
        cacheJson(cacheKey, messages);
        log.info("[SESSION-MSG] userId={} session={} 缓存未命中, 从DB加载最近 {} 条并缓存", userId, sessionId, messages.size());
        return messages;
    }

    /**
     * List session infos for the given user, paginated and ordered by most-recently
     * updated.
     *
     * <p>Backed by a Redis ZSET (ordered index of session ids scored by updatedAt) plus
     * per-session meta cache. Falls back to a direct DB paginated query if Redis is
     * unavailable or the ZSET is cold.
     */
    public List<SessionInfo> listSessions(Long userId, long offset, int limit) {
        List<SessionInfo> cached = listFromZset(userId, offset, limit);
        if (cached != null) {
            log.info("[SESSION-LIST] userId={} offset={} limit={} 命中ZSET缓存, 返回 {} 条",
                    userId, offset, limit, cached.size());
            return cached;
        }
        // Redis unavailable or ZSET missing → fall back to DB pagination.
        List<SessionInfo> fromDb = sessionMapper
                .findByUserIdOrderByUpdatedAtDescPaged(userId, offset, limit)
                .stream().map(this::toSessionInfo).toList();
        log.info("[SESSION-LIST] userId={} offset={} limit={} ZSET未命中, 降级DB查询, 返回 {} 条",
                userId, offset, limit, fromDb.size());
        return fromDb;
    }

    /**
     * Legacy convenience: list the first {@code limit} sessions (default 10).
     */
    public List<SessionInfo> listSessions(Long userId) {
        return listSessions(userId, 0, 20);
    }

    /**
     * Attempt to read a page of sessions from the ZSET + meta caches. Returns null when
     * Redis is unavailable or the ZSET does not exist (caller falls back to DB).
     */
    private List<SessionInfo> listFromZset(Long userId, long offset, int limit) {
        String zsetKey = zsetKey(userId);
        try {
            if (Boolean.FALSE.equals(redisTemplate.hasKey(zsetKey))) {
                log.info("[SESSION-LIST] userId={} ZSET key 不存在 (冷缓存)", userId);
                return null;
            }
            Set<String> ids = redisTemplate.opsForZSet().reverseRange(zsetKey, offset, offset + limit - 1);
            if (ids == null || ids.isEmpty()) {
                log.info("[SESSION-LIST] userId={} offset={} ZSET 该页无数据", userId, offset);
                return List.of();
            }
            // Batch-fetch per-session meta; missing entries are back-filled from DB.
            List<SessionInfo> result = new ArrayList<>();
            int metaHit = 0;
            int backfilled = 0;
            for (String id : ids) {
                SessionInfo meta = readSessionMeta(userId, id);
                if (meta != null) {
                    result.add(meta);
                    metaHit++;
                } else {
                    // Meta missing (cache evicted) → back-fill from DB.
                    SessionEntity e = sessionMapper.selectById(id);
                    if (e != null && userId.equals(e.getUserId())) {
                        SessionInfo info = toSessionInfo(e);
                        writeSessionMeta(userId, info);
                        result.add(info);
                        backfilled++;
                    }
                }
            }
            log.info("[SESSION-LIST] userId={} ZSET取到 {} 个id, meta命中 {} 个, DB回填 {} 个",
                    userId, ids.size(), metaHit, backfilled);
            return result;
        } catch (Exception e) {
            log.warn("Failed to read session list from ZSET for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Delete a session and its messages, verifying ownership.
     */
    public boolean deleteSession(Long userId, String sessionId) {
        SessionEntity entity = sessionMapper.selectById(sessionId);
        if (entity == null || !userId.equals(entity.getUserId())) {
            return false;
        }
        messageMapper.delete(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getUserId, userId)
                .eq(MessageEntity::getSessionId, sessionId));
        sessionMapper.deleteById(sessionId);
        invalidateMessagesCache(userId, sessionId);
        removeFromZsetAndMeta(userId, sessionId);
        log.info("Deleted session: {} for user {}", sessionId, userId);
        return true;
    }

    /**
     * Clear all sessions and messages belonging to the given user.
     */
    public void clearAll(Long userId) {
        List<String> ids = sessionMapper.selectList(
                        Wrappers.<SessionEntity>lambdaQuery().eq(SessionEntity::getUserId, userId))
                .stream().map(SessionEntity::getId).toList();
        sessionMapper.delete(Wrappers.<SessionEntity>lambdaQuery().eq(SessionEntity::getUserId, userId));
        messageMapper.delete(Wrappers.<MessageEntity>lambdaQuery().eq(MessageEntity::getUserId, userId));
        clearUserZset(userId);
        ids.forEach(id -> {
            invalidateMessagesCache(userId, id);
            deleteSessionMeta(userId, id);
        });
        log.info("All sessions cleared for user {}", userId);
    }

    // ========== Cache helpers ==========

    private static String zsetKey(Long userId) {
        return CACHE_SESSION_ZSET_PREFIX + userId;
    }

    private static String metaKey(Long userId, String sessionId) {
        return CACHE_SESSION_META_PREFIX + userId + ":" + sessionId;
    }

    private static String messagesCacheKey(Long userId, String sessionId) {
        return CACHE_SESSION_MESSAGES_PREFIX + userId + ":" + sessionId;
    }

    /** Update (or insert) the ZSET score and per-session meta for the given entity. */
    private void updateZsetAndMeta(Long userId, SessionEntity entity) {
        try {
            double score = toEpochMilli(entity.getUpdatedAt());
            redisTemplate.opsForZSet().add(zsetKey(userId), entity.getId(), score);
            redisTemplate.expire(zsetKey(userId), ZSET_TTL);
            writeSessionMeta(userId, toSessionInfo(entity));
            log.info("[SESSION-CACHE] userId={} session={} 更新ZSET(score={}) + meta缓存",
                    userId, entity.getId(), score);
        } catch (Exception e) {
            log.warn("Failed to update session ZSET/meta for user {} session {}", userId, entity.getId(), e);
        }
    }

    private void removeFromZsetAndMeta(Long userId, String sessionId) {
        try {
            redisTemplate.opsForZSet().remove(zsetKey(userId), sessionId);
        } catch (Exception e) {
            log.warn("Failed to remove session from ZSET for user {}: {}", userId, e.getMessage());
        }
        deleteSessionMeta(userId, sessionId);
    }

    private void clearUserZset(Long userId) {
        try {
            redisTemplate.delete(zsetKey(userId));
        } catch (Exception e) {
            log.warn("Failed to clear session ZSET for user {}: {}", userId, e.getMessage());
        }
    }

    private void writeSessionMeta(Long userId, SessionInfo info) {
        try {
            redisTemplate.opsForValue().set(metaKey(userId, info.id()),
                    objectMapper.writeValueAsString(info), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to write session meta {} for user {}", info.id(), userId, e);
        }
    }

    private SessionInfo readSessionMeta(Long userId, String sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(metaKey(userId, sessionId));
            if (json != null) {
                return objectMapper.readValue(json, SESSION_INFO_TYPE);
            }
        } catch (Exception e) {
            log.warn("Failed to read session meta {} for user {}", sessionId, userId, e);
        }
        return null;
    }

    private void deleteSessionMeta(Long userId, String sessionId) {
        try {
            redisTemplate.delete(metaKey(userId, sessionId));
        } catch (Exception e) {
            log.warn("Failed to delete session meta {} for user {}", sessionId, userId, e);
        }
    }

    /**
     * Append a newly-added message to the cached message list instead of invalidating
     * the whole cache. This is safe because writes for a single user/session are
     * serialized (single sign-on + per-session key), so there is no concurrent
     * read-modify-write race.
     */
    private void updateMessagesCache(Long userId, String sessionId, AgentMessage message) {
        try {
            String cacheKey = messagesCacheKey(userId, sessionId);
            Optional<List<AgentMessage>> cached = readCache(cacheKey, MESSAGE_LIST_TYPE);
            List<AgentMessage> updated = new ArrayList<>();
            if (cached.isPresent()) {
                updated.addAll(cached.get());
            }
            updated.add(message);
            // Keep only the most recent MAX_MESSAGES_PER_SESSION messages.
            if (updated.size() > MAX_MESSAGES_PER_SESSION) {
                updated = new ArrayList<>(updated.subList(updated.size() - MAX_MESSAGES_PER_SESSION, updated.size()));
            }
            cacheJson(cacheKey, updated);
            log.info("[SESSION-MSG] userId={} session={} 更新消息缓存, 现 {} 条", userId, sessionId, updated.size());
        } catch (Exception e) {
            log.warn("Failed to update messages cache for session {} user {}", sessionId, userId, e);
        }
    }

    private void invalidateMessagesCache(Long userId, String sessionId) {
        try {
            redisTemplate.delete(messagesCacheKey(userId, sessionId));
        } catch (Exception e) {
            log.warn("Failed to invalidate messages cache for session {} user {}", sessionId, userId, e);
        }
    }

    private void cacheJson(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to write cache key {}", key, e);
        }
    }

    private <T> Optional<T> readCache(String key, TypeReference<T> type) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return Optional.of(objectMapper.readValue(json, type));
            }
        } catch (Exception e) {
            log.warn("Failed to read cache key {}", key, e);
        }
        return Optional.empty();
    }

    private static double toEpochMilli(LocalDateTime ldt) {
        return ldt != null ? ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : 0d;
    }

    // ========== Conversion helpers ==========

    private ConversationSession toConversationSession(SessionEntity e) {
        return new ConversationSession(e.getId(), e.getName(),
                toInstant(e.getCreatedAt()), toInstant(e.getUpdatedAt()), e.getMessageCount());
    }

    private SessionInfo toSessionInfo(SessionEntity e) {
        return new SessionInfo(e.getId(), e.getName(),
                toInstant(e.getCreatedAt()), toInstant(e.getUpdatedAt()), e.getMessageCount());
    }

    private AgentMessage toAgentMessage(MessageEntity e) {
        AgentMessage.Role role = AgentMessage.Role.valueOf(e.getRole());
        List<AgentMessage.ToolCall> toolCalls = List.of();
        if (e.getToolCallsJson() != null && !e.getToolCallsJson().isBlank()) {
            try {
                toolCalls = objectMapper.readValue(e.getToolCallsJson(),
                        new TypeReference<List<AgentMessage.ToolCall>>() {});
            } catch (Exception ex) {
                log.warn("Failed to deserialize toolCalls for message {}", e.getId(), ex);
            }
        }
        return new AgentMessage(role, e.getContent(), toolCalls, e.getToolCallId());
    }

    private MessageEntity toMessageEntity(Long userId, String sessionId, AgentMessage m) {
        MessageEntity e = new MessageEntity();
        e.setUserId(userId);
        e.setSessionId(sessionId);
        e.setRole(m.role().name());
        e.setContent(m.content());
        e.setToolCallId(m.toolCallId());
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            try {
                e.setToolCallsJson(objectMapper.writeValueAsString(m.toolCalls()));
            } catch (Exception ex) {
                log.warn("Failed to serialize toolCalls for session {}", sessionId, ex);
            }
        }
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt != null ? ldt.atZone(ZoneId.systemDefault()).toInstant() : null;
    }

    /**
     * Represents a conversation session (metadata only; messages live in MySQL).
     */
    public record ConversationSession(String id, String name, Instant createdAt, Instant updatedAt, int messageCount) {

        public SessionInfo toInfo() {
            return new SessionInfo(id, name, createdAt, updatedAt, messageCount);
        }
    }
}
