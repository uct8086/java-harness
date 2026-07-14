package uct8086.ai.persistence;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Persistent representation of a single message within a session.
 */
@Data
@Table("harness_message")
public class MessageEntity {

    @Id
    @Column("id")
    private Long id;

    @Column("session_id")
    private String sessionId;

    @Column("role")
    private String role;

    @Column("content")
    private String content;

    @Column("tool_calls_json")
    private String toolCallsJson;

    @Column("tool_call_id")
    private String toolCallId;

    @Column("created_at")
    private LocalDateTime createdAt;
}
