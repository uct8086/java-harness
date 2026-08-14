package uct8086.ai.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * MySQL-backed implementation of {@link MemoryStore}.
 *
 * <p>Replaces the file-based {@link FileMemoryStore} so that memory is:
 * <ul>
 *   <li>persisted in the shared database (survives restarts),</li>
 *   <li>shared across horizontally-scaled instances,</li>
 *   <li>queryable by user / category / keyword.</li>
 * </ul>
 *
 * <p>All operations remain scoped to a {@code userId}, preserving per-user isolation.
 */
@Component
public class MySqlMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MySqlMemoryStore.class);

    private final MemoryMapper memoryMapper;
    private final MemoryVectorService memoryVectorService;

    public MySqlMemoryStore(MemoryMapper memoryMapper, MemoryVectorService memoryVectorService) {
        this.memoryMapper = memoryMapper;
        this.memoryVectorService = memoryVectorService;
    }

    @Override
    public MemoryEntry save(Long userId, MemoryEntry entry) {
        MemoryEntity entity = MemoryEntity.from(userId, entry);
        MemoryEntity existing = memoryMapper.selectById(entry.id());
        if (existing != null) {
            memoryMapper.updateById(entity);
        } else {
            memoryMapper.insert(entity);
        }
        // Index into the vector store for relevance-based retrieval.
        memoryVectorService.index(userId, entry);
        log.debug("Saved memory entry: {} ({}) for user {}", entry.id(), entry.category(), userId);
        return entry;
    }

    @Override
    public Optional<MemoryEntry> get(Long userId, String id) {
        MemoryEntity entity = memoryMapper.selectOne(
                Wrappers.<MemoryEntity>lambdaQuery()
                        .eq(MemoryEntity::getUserId, userId)
                        .eq(MemoryEntity::getId, id));
        return entity != null ? Optional.of(entity.toEntry()) : Optional.empty();
    }

    @Override
    public List<MemoryEntry> getByCategory(Long userId, String category) {
        return memoryMapper.findByUserIdAndCategoryOrderByCreatedAtAsc(userId, category)
                .stream().map(MemoryEntity::toEntry).toList();
    }

    @Override
    public List<MemoryEntry> getAll(Long userId) {
        return memoryMapper.findByUserIdOrderByCreatedAtAsc(userId)
                .stream().map(MemoryEntity::toEntry).toList();
    }

    @Override
    public MemoryEntry update(Long userId, MemoryEntry entry) {
        MemoryEntity entity = MemoryEntity.from(userId, entry);
        memoryMapper.update(entity, Wrappers.<MemoryEntity>lambdaUpdate()
                .eq(MemoryEntity::getUserId, userId)
                .eq(MemoryEntity::getId, entry.id()));
        // Re-index (content may have changed).
        memoryVectorService.index(userId, entry);
        return entry;
    }

    @Override
    public boolean delete(Long userId, String id) {
        int deleted = memoryMapper.delete(Wrappers.<MemoryEntity>lambdaQuery()
                .eq(MemoryEntity::getUserId, userId)
                .eq(MemoryEntity::getId, id));
        log.debug("Deleted memory entry: {} for user {}", id, userId);
        return deleted > 0;
    }

    @Override
    public List<MemoryEntry> search(Long userId, String keyword) {
        return memoryMapper.searchByUserId(userId, keyword)
                .stream().map(MemoryEntity::toEntry).toList();
    }

    @Override
    public void clear(Long userId) {
        memoryMapper.delete(Wrappers.<MemoryEntity>lambdaQuery()
                .eq(MemoryEntity::getUserId, userId));
        log.info("Memory cleared for user {}", userId);
    }
}
