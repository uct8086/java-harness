package uct8086.ai.core.cost;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis-Plus mapper for {@link CostUsageEntity}.
 */
@Mapper
public interface CostUsageMapper extends BaseMapper<CostUsageEntity> {

    /**
     * Aggregate token/cost usage for a given user + session.
     */
    @Select("""
            SELECT COALESCE(SUM(input_tokens), 0)  AS inputTokens,
                   COALESCE(SUM(output_tokens), 0) AS outputTokens,
                   COALESCE(SUM(total_tokens), 0)  AS totalTokens,
                   COALESCE(SUM(cost), 0)          AS cost
            FROM cost_usage
            WHERE user_id = #{userId} AND session_id = #{sessionId}
            """)
    UsageAggregate sumByUserAndSession(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    /**
     * Aggregate token/cost usage across all sessions for a given user.
     */
    @Select("""
            SELECT COALESCE(SUM(input_tokens), 0)  AS inputTokens,
                   COALESCE(SUM(output_tokens), 0) AS outputTokens,
                   COALESCE(SUM(total_tokens), 0)  AS totalTokens,
                   COALESCE(SUM(cost), 0)          AS cost
            FROM cost_usage
            WHERE user_id = #{userId}
            """)
    UsageAggregate sumByUser(@Param("userId") Long userId);

    /**
     * Delete all usage records for a given user + session.
     */
    @Delete("DELETE FROM cost_usage WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int deleteByUserAndSession(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    /**
     * Delete all usage records for a given user.
     */
    @Delete("DELETE FROM cost_usage WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") Long userId);
}
