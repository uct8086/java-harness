package uct8086.ai.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uct8086.ai.auth.entity.UserEntity;
import uct8086.ai.auth.mapper.UserMapper;
import uct8086.ai.persistence.MessageEntity;
import uct8086.ai.persistence.MessageMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Automatically consolidates a user's conversation history into long-term memories.
 *
 * <p>Runs on a schedule, walks every enabled user, reads messages since the last
 * consolidation watermark, and asks the chat model to extract durable user
 * preferences/facts. Extracted entries are written to {@link MemoryStore} (which also
 * indexes them into the vector store).
 *
 * <p>Only "user preferences / facts" are extracted (not task process), keeping memory
 * lean and high-signal. The watermark is stored in Redis so consolidation is
 * incremental and idempotent across instances.
 */
@Service
public class MemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);

    private static final String WATERMARK_PREFIX = "harness:memory:watermark:";
    private static final int MAX_MESSAGES_PER_RUN = 200;
    private static final int MIN_MESSAGES_TO_CONSOLIDATE = 4;

    private final UserMapper userMapper;
    private final MessageMapper messageMapper;
    private final MemoryStore memoryStore;
    private final ChatModel chatModel;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public MemoryConsolidationService(UserMapper userMapper,
                                      MessageMapper messageMapper,
                                      MemoryStore memoryStore,
                                      @Qualifier("openAiChatModel") ChatModel chatModel,
                                      StringRedisTemplate redisTemplate,
                                      ObjectMapper objectMapper) {
        this.userMapper = userMapper;
        this.messageMapper = messageMapper;
        this.memoryStore = memoryStore;
        this.chatModel = chatModel;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Periodic consolidation. Default: every hour. Override via
     * {@code uct8086.ai.memory.consolidation-cron} in application.yml.
     */
    @Scheduled(cron = "${uct8086.ai.memory.consolidation-cron:0 0 * * * *}")
    public void consolidateAllUsers() {
        log.info("[MEMORY-CONSOLIDATE] 定时记忆总结任务触发");
        List<UserEntity> users = userMapper.selectList(null);
        if (users == null || users.isEmpty()) {
            log.info("[MEMORY-CONSOLIDATE] 无用户，跳过");
            return;
        }
        int processed = 0;
        for (UserEntity user : users) {
            if (!user.isEnabled()) {
                continue;
            }
            try {
                consolidate(user.getId());
                processed++;
            } catch (Exception e) {
                log.warn("Memory consolidation failed for user {}", user.getId(), e);
            }
        }
        log.info("[MEMORY-CONSOLIDATE] 定时总结完成，处理 {} / {} 个用户", processed, users.size());
    }

    /**
     * Consolidate a single user's new messages into memories.
     */
    public void consolidate(Long userId) {
        long watermark = loadWatermark(userId);
        List<MessageEntity> messages =
                messageMapper.findByUserIdAndIdGreaterThan(userId, watermark, MAX_MESSAGES_PER_RUN);
        if (messages.size() < MIN_MESSAGES_TO_CONSOLIDATE) {
            log.info("[MEMORY-CONSOLIDATE] userId={} 新消息 {} 条(水位线={})，不足 {} 条，跳过",
                    userId, messages.size(), watermark, MIN_MESSAGES_TO_CONSOLIDATE);
            return; // not enough new material
        }

        log.info("[MEMORY-CONSOLIDATE] userId={} 开始总结，读取新消息 {} 条(水位线={})",
                userId, messages.size(), watermark);
        String transcript = buildTranscript(messages);
        List<ExtractedMemory> extracted = extractMemories(transcript);
        if (extracted.isEmpty()) {
            // Nothing worth remembering; still advance the watermark.
            saveWatermark(userId, messages.get(messages.size() - 1).getId());
            log.info("[MEMORY-CONSOLIDATE] userId={} 无值得记忆的内容，推进水位线到 {}",
                    userId, messages.get(messages.size() - 1).getId());
            return;
        }

        int saved = 0;
        for (ExtractedMemory m : extracted) {
            if (m.content() == null || m.content().isBlank()) {
                continue;
            }
            memoryStore.save(userId, new MemoryEntry(
                    m.category() == null || m.category().isBlank() ? "general" : m.category(),
                    m.content().trim()));
            saved++;
            log.info("[MEMORY-CONSOLIDATE] userId={} 提取记忆 [{}] {}", userId, m.category(), m.content());
        }
        saveWatermark(userId, messages.get(messages.size() - 1).getId());
        log.info("[MEMORY-CONSOLIDATE] userId={} 总结完成，保存 {} 条记忆 (从 {} 条消息)",
                saved, userId, messages.size());
    }

    /**
     * Force-consolidate a user now (e.g. triggered manually via API/admin).
     */
    public int consolidateNow(Long userId) {
        long watermark = loadWatermark(userId);
        List<MessageEntity> messages =
                messageMapper.findByUserIdAndIdGreaterThan(userId, watermark, MAX_MESSAGES_PER_RUN);
        if (messages.isEmpty()) {
            return 0;
        }
        String transcript = buildTranscript(messages);
        List<ExtractedMemory> extracted = extractMemories(transcript);
        int saved = 0;
        for (ExtractedMemory m : extracted) {
            if (m.content() == null || m.content().isBlank()) {
                continue;
            }
            memoryStore.save(userId, new MemoryEntry(
                    m.category() == null || m.category().isBlank() ? "general" : m.category(),
                    m.content().trim()));
            saved++;
        }
        saveWatermark(userId, messages.get(messages.size() - 1).getId());
        return saved;
    }

    private String buildTranscript(List<MessageEntity> messages) {
        StringBuilder sb = new StringBuilder();
        for (MessageEntity m : messages) {
            String role = "user".equals(m.getRole()) ? "用户" : "助手";
            sb.append(role).append(": ").append(m.getContent()).append("\n");
        }
        return sb.toString();
    }

    private List<ExtractedMemory> extractMemories(String transcript) {
        String prompt = """
                你是一个记忆提取器。请阅读下面的对话，提取关于「用户」的稳定偏好和事实（例如：用户的姓名、职业、技术栈偏好、项目背景、沟通习惯、喜欢的工具等）。
                只提取长期有用的事实和偏好，不要提取对话过程、临时问题或助手已经知道的一般性内容。
                如果没有值得长期记住的内容，输出空数组 []。

                输出严格的 JSON 数组，每个元素包含 category 和 content 字段，例如：
                [{"category":"preference","content":"用户偏好用中文回答"}]

                对话如下：
                %s
                """.formatted(transcript);

        try {
            String response = ChatClient.builder(chatModel).build()
                    .prompt().user(prompt).call().content();
            if (response == null || response.isBlank()) {
                return List.of();
            }
            String json = extractJson(response);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Memory extraction failed", e);
            return List.of();
        }
    }

    /** Strip markdown code fences / surrounding text to isolate the JSON array. */
    private String extractJson(String response) {
        String s = response.trim();
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return "[]";
    }

    private long loadWatermark(Long userId) {
        String raw = redisTemplate.opsForValue().get(WATERMARK_PREFIX + userId);
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void saveWatermark(Long userId, long messageId) {
        redisTemplate.opsForValue().set(WATERMARK_PREFIX + userId, String.valueOf(messageId));
    }

    /** A single extracted memory. */
    public record ExtractedMemory(String category, String content) {}
}
