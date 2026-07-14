package uct8086.ai.persistence;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Persistent representation of a conversation session.
 *
 * <p>Implements {@link Persistable} so Spring Data JDBC can distinguish
 * new entities (INSERT) from existing ones (UPDATE) when the primary key
 * is manually assigned (UUID string).
 */
@Data
@Table("harness_session")
public class SessionEntity implements Persistable<String> {

    @Id
    @Column("id")
    private String id;

    @Column("name")
    private String name;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("message_count")
    private int messageCount;

    /** Set to {@code false} to force an INSERT on the next {@code save()} call. */
    @Transient
    private boolean persisted = true;

    /**
     * Mark this entity as new so the next {@code Repository.save()} call
     * issues an INSERT rather than an UPDATE.
     */
    public void markNew() {
        this.persisted = false;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }
}
