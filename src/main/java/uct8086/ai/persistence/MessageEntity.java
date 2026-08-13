package uct8086.ai.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistent representation of a single message within a session.
 */
@Data
@TableName("harness_message")
public class MessageEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("role")
    private String role;

    @TableField("content")
    private String content;

    @TableField("tool_calls_json")
    private String toolCallsJson;

    @TableField("tool_call_id")
    private String toolCallId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
