package uct8086.ai.core.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import uct8086.ai.common.model.AgentMessage;
import uct8086.ai.common.model.SessionInfo;
import uct8086.ai.persistence.MessageEntity;
import uct8086.ai.persistence.MessageRepository;
import uct8086.ai.persistence.SessionEntity;
import uct8086.ai.persistence.SessionRepository;
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
 * <p>Features:
 * <ul>
 *   <li>Create new sessions (persisted to MySQL)</li>
 *   <li>Resume existing sessions by ID (loaded from MySQL)</li>
 *   <li>List session history (cached in Redis)</li>
 *   <li>Track messages per session (persisted to MySQL, cached in Redis)</li>
 * </ul>
 *
 * <p>Redis cache keys:
 * <ul>
 *   <li>{@code harness:session:list} -&gt; list of {@link SessionInfo} (TTL 10m)</li>
 *   <li>{@code harness:session:messages:{id}} -&gt; list of {@link AgentMessage} (TTL 10m)</li>
 * </ul>
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private static final String CACHE_SESSION_LIST = "harness:session:list";
    private static final String CACHE_SESSION_MESSAGES_PREFIX = "harness:session:messages:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private static final TypeReference<List<SessionInfo>> SESSION_INFO_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<AgentMessage>> MESSAGE_LIST_TYPE = new TypeReference<>() {};

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionManager(SessionRepository sessionRepository,
                          MessageRepository messageRepository,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new session.
     */
    public ConversationSession createSession(String name) {
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        SessionEntity entity = new SessionEntity();
        entity.setId(id);
        entity.setName(name != null ? name : "session");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setMessageCount(0);
        entity.markNew(); // force INSERT for a manually-assigned UUID
        sessionRepository.save(entity);
        invalidateListCache();
        log.info("Created session: {} ({})", name, id);
        return toConversationSession(entity);
    }

    /**
     * Create a new unnamed session.
     * Uses a UUID-derived suffix to avoid the concurrency race and full-table
     * {@code count()} scan of the previous "session-<count>" naming scheme.
     */
    public ConversationSession createSession() {
        return createSession("session-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * Get a session by ID.
     */
    public Optional<ConversationSession> getSession(String sessionId) {
        return sessionRepository.findById(sessionId).map(this::toConversationSession);
    }

    /**
     * Add a message to a session (persisted to MySQL, cache invalidated).
     */
    public void addMessage(String sessionId, AgentMessage message) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            log.warn("Session not found for addMessage: {}", sessionId);
            return;
        }
        messageRepository.save(toMessageEntity(sessionId, message));

        entity.setMessageCount(entity.getMessageCount() + 1);
        entity.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(entity);

        invalidateMessagesCache(sessionId);
        invalidateListCache();
        log.debug("Added {} message to session {} (total {})", message.role(), sessionId, entity.getMessageCount());
    }

    /**
     * Get messages from a session (Redis-cached, falls back to MySQL).
     */
    public List<AgentMessage> getMessages(String sessionId) {
        String cacheKey = CACHE_SESSION_MESSAGES_PREFIX + sessionId;
        Optional<List<AgentMessage>> cached = readCache(cacheKey, MESSAGE_LIST_TYPE);
        if (cached.isPresent()) {
            log.debug("Cache hit for messages of session {}", sessionId);
            return cached.get();
        }
        List<MessageEntity> entities = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<AgentMessage> messages = entities.stream().map(this::toAgentMessage).toList();
        cacheJson(cacheKey, messages);
        log.debug("Loaded {} messages for session {} from DB (cached)", messages.size(), sessionId);
        return messages;
    }

    /**
     * List all session infos (Redis-cached, falls back to MySQL).
     */
    public List<SessionInfo> listSessions() {
        Optional<List<SessionInfo>> cached = readCache(CACHE_SESSION_LIST, SESSION_INFO_LIST_TYPE);
        if (cached.isPresent()) {
            log.debug("Cache hit for session list");
            return cached.get();
        }
        List<SessionInfo> sessions = sessionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(this::toSessionInfo)
                .toList();
        cacheJson(CACHE_SESSION_LIST, sessions);
        log.debug("Loaded {} sessions from DB (cached)", sessions.size());
        return sessions;
    }

    /**
     * Delete a session and its messages.
     */
    public boolean deleteSession(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            return false;
        }
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        invalidateMessagesCache(sessionId);
        invalidateListCache();
        log.info("Deleted session: {}", sessionId);
        return true;
    }

    /**
     * Clear all sessions and messages.
     */
    public void clearAll() {
        List<String> ids = sessionRepository.findAll().stream().map(SessionEntity::getId).toList();
        sessionRepository.deleteAll();
        messageRepository.deleteAll();
        invalidateListCache();
        ids.forEach(this::invalidateMessagesCache);
        log.info("All sessions cleared");
    }

    // ========== Cache helpers ==========

    private void invalidateListCache() {
        redisTemplate.delete(CACHE_SESSION_LIST);
    }

    private void invalidateMessagesCache(String sessionId) {
        redisTemplate.delete(CACHE_SESSION_MESSAGES_PREFIX + sessionId);
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

    private MessageEntity toMessageEntity(String sessionId, AgentMessage m) {
        MessageEntity e = new MessageEntity();
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
