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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 *   <li>{@code harness:session:list:{userId}} -&gt; list of {@link SessionInfo} (TTL 10m)</li>
 *   <li>{@code harness:session:messages:{userId}:{id}} -&gt; list of {@link AgentMessage} (TTL 10m)</li>
 * </ul>
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private static final String CACHE_SESSION_LIST_PREFIX = "harness:session:list:";
    private static final String CACHE_SESSION_MESSAGES_PREFIX = "harness:session:messages:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private static final TypeReference<List<SessionInfo>> SESSION_INFO_LIST_TYPE = new TypeReference<>() {};
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
        invalidateListCache(userId);
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

        invalidateMessagesCache(userId, sessionId);
        invalidateListCache(userId);
        log.debug("Added {} message to session {} (total {})", message.role(), sessionId, entity.getMessageCount());
    }

    /**
     * Get messages from a session owned by the given user (Redis-cached).
     */
    public List<AgentMessage> getMessages(Long userId, String sessionId) {
        String cacheKey = messagesCacheKey(userId, sessionId);
        Optional<List<AgentMessage>> cached = readCache(cacheKey, MESSAGE_LIST_TYPE);
        if (cached.isPresent()) {
            log.debug("Cache hit for messages of session {}", sessionId);
            return cached.get();
        }
        List<MessageEntity> entities = messageMapper.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
        List<AgentMessage> messages = entities.stream().map(this::toAgentMessage).toList();
        cacheJson(cacheKey, messages);
        log.debug("Loaded {} messages for session {} from DB (cached)", messages.size(), sessionId);
        return messages;
    }

    /**
     * List session infos for the given user (Redis-cached).
     */
    public List<SessionInfo> listSessions(Long userId) {
        String cacheKey = listCacheKey(userId);
        Optional<List<SessionInfo>> cached = readCache(cacheKey, SESSION_INFO_LIST_TYPE);
        if (cached.isPresent()) {
            log.debug("Cache hit for session list of user {}", userId);
            return cached.get();
        }
        List<SessionInfo> sessions = sessionMapper.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toSessionInfo)
                .toList();
        cacheJson(cacheKey, sessions);
        log.debug("Loaded {} sessions for user {} from DB (cached)", sessions.size(), userId);
        return sessions;
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
        invalidateListCache(userId);
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
        invalidateListCache(userId);
        ids.forEach(id -> invalidateMessagesCache(userId, id));
        log.info("All sessions cleared for user {}", userId);
    }

    // ========== Cache helpers ==========

    private static String listCacheKey(Long userId) {
        return CACHE_SESSION_LIST_PREFIX + userId;
    }

    private static String messagesCacheKey(Long userId, String sessionId) {
        return CACHE_SESSION_MESSAGES_PREFIX + userId + ":" + sessionId;
    }

    private void invalidateListCache(Long userId) {
        redisTemplate.delete(listCacheKey(userId));
    }

    private void invalidateMessagesCache(Long userId, String sessionId) {
        redisTemplate.delete(messagesCacheKey(userId, sessionId));
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
