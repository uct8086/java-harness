package uct8086.ai.core.cost;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistent representation of a single cost/token usage record.
 * Each {@code costTracker.record(...)} call inserts one row, enabling
 * cross-instance aggregation and historical reporting.
 */
@Data
@TableName("cost_usage")
public class CostUsageEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private String sessionId;

    @TableField("input_tokens")
    private Long inputTokens;

    @TableField("output_tokens")
    private Long outputTokens;

    @TableField("total_tokens")
    private Long totalTokens;

    @TableField("cost")
    private Double cost;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
