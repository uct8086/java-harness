package uct8086.ai.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges {@link MySqlMemoryStore} (truth source) with the pgvector {@link VectorStore}
 * (similarity index) so that memories can be retrieved by relevance instead of being
 * injected wholesale into every prompt.
 *
 * <p>Memories are indexed as documents carrying metadata:
 * <ul>
 *   <li>{@code type=memory} — distinguishes memories from knowledge-base documents</li>
 *   <li>{@code userId} — per-user isolation</li>
 *   <li>{@code memoryId} — back-reference to the MySQL row</li>
 * </ul>
 *
 * <p>Retrieval searches the shared vector store, then filters by {@code type} and
 * {@code userId} in code (rather than relying on a DB-level filter expression, which is
 * provider-dependent). This keeps a single vector store while isolating memories.
 */
@Component
public class MemoryVectorService {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorService.class);

    public static final String METADATA_TYPE = "type";
    public static final String METADATA_TYPE_MEMORY = "memory";
    public static final String METADATA_USER_ID = "userId";
    public static final String METADATA_MEMORY_ID = "memoryId";

    @Autowired(required = false)
    @Qualifier("pgVectorStore")
    private VectorStore vectorStore;

    /**
     * Index a memory (embed its content) into the vector store.
     */
    public void index(Long userId, MemoryEntry entry) {
        if (vectorStore == null) {
            log.warn("Vector store unavailable; memory not indexed: {}", entry.id());
            return;
        }
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(METADATA_TYPE, METADATA_TYPE_MEMORY);
            metadata.put(METADATA_USER_ID, userId);
            metadata.put(METADATA_MEMORY_ID, entry.id());
            metadata.put("category", entry.category());
            Document doc = new Document(entry.id(), entry.content(), metadata);
            vectorStore.add(List.of(doc));
            log.info("[MEMORY-INDEX] Indexed memory id={} category={} userId={} into pgvector",
                    entry.id(), entry.category(), userId);
        } catch (Exception e) {
            log.warn("Failed to index memory {} for user {}", entry.id(), userId, e);
        }
    }

    /**
     * Retrieve the top-K memories relevant to the query for a given user.
     *
     * <p>Uses vector similarity recall, then filters by {@code type} and {@code userId}.
     */
    public List<MemoryEntry> search(Long userId, String query, int topK) {
        if (vectorStore == null) {
            log.warn("[MEMORY-SEARCH] pgvector unavailable, skip memory retrieval (userId={})", userId);
            return List.of();
        }
        try {
            // Fetch more than needed, then filter by type + userId in code.
            int fetchTopK = Math.max(topK * 5, 20);
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(fetchTopK).build());
            long total = docs.size();
            // Compare userId as strings: PgVectorStore may read the numeric metadata
            // back as Integer/String rather than Long, so Long.equals() fails silently.
            String userIdStr = String.valueOf(userId);
            long memoryDocs = docs.stream()
                    .filter(d -> METADATA_TYPE_MEMORY.equals(d.getMetadata().get(METADATA_TYPE)))
                    .count();
            long sameUserDocs = docs.stream()
                    .filter(d -> METADATA_TYPE_MEMORY.equals(d.getMetadata().get(METADATA_TYPE)))
                    .filter(d -> userIdStr.equals(String.valueOf(d.getMetadata().get(METADATA_USER_ID))))
                    .count();

            List<MemoryEntry> result = docs.stream()
                    .filter(d -> METADATA_TYPE_MEMORY.equals(d.getMetadata().get(METADATA_TYPE)))
                    .filter(d -> userIdStr.equals(String.valueOf(d.getMetadata().get(METADATA_USER_ID))))
                    .limit(topK)
                    .map(d -> new MemoryEntry(
                            (String) d.getMetadata().get(METADATA_MEMORY_ID),
                            String.valueOf(d.getMetadata().getOrDefault("category", "general")),
                            d.getText(),
                            null,
                            null))
                    .toList();

            log.info("[MEMORY-SEARCH] userId={} query='{}' pgvector 返回 {} 条(其中 memory 类型 {} 条, 本用户 {} 条), 最终注入 {} 条",
                    userId, truncate(query, 50), total, memoryDocs, sameUserDocs, result.size());
            return result;
        } catch (Exception e) {
            log.warn("Memory vector search failed for user {}", userId, e);
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public boolean isAvailable() {
        return vectorStore != null;
    }
}
