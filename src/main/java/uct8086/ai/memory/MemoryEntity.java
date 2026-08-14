package uct8086.ai.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Persistent representation of a memory entry.
 */
@Data
@TableName("harness_memory")
public class MemoryEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("user_id")
    private Long userId;

    @TableField("category")
    private String category;

    @TableField("content")
    private String content;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * Convert to the domain {@link MemoryEntry} record.
     */
    public MemoryEntry toEntry() {
        return new MemoryEntry(
                id,
                category,
                content,
                toInstant(createdAt),
                toInstant(updatedAt)
        );
    }

    /**
     * Build an entity from a domain {@link MemoryEntry} for the given user.
     */
    public static MemoryEntity from(Long userId, MemoryEntry entry) {
        MemoryEntity e = new MemoryEntity();
        e.setId(entry.id());
        e.setUserId(userId);
        e.setCategory(entry.category());
        e.setContent(entry.content());
        e.setCreatedAt(fromInstant(entry.createdAt()));
        e.setUpdatedAt(fromInstant(entry.updatedAt()));
        return e;
    }

    private static Instant toInstant(LocalDateTime dt) {
        return dt == null ? null : dt.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static LocalDateTime fromInstant(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
