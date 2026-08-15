package uct8086.ai.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis-Plus mapper for {@link MessageEntity}.
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

    @Select("SELECT * FROM harness_message WHERE user_id = #{userId} AND session_id = #{sessionId} ORDER BY created_at ASC")
    List<MessageEntity> findByUserIdAndSessionIdOrderByCreatedAtAsc(@Param("userId") Long userId,
                                                                    @Param("sessionId") String sessionId);

    /**
     * Fetch a user's messages after a given id (for incremental memory consolidation).
     */
    @Select("SELECT * FROM harness_message WHERE user_id = #{userId} AND id > #{afterId} ORDER BY id ASC LIMIT #{limit}")
    List<MessageEntity> findByUserIdAndIdGreaterThan(@Param("userId") Long userId,
                                                     @Param("afterId") Long afterId,
                                                     @Param("limit") int limit);

    /**
     * Fetch a user's messages after a given timestamp (for time-window consolidation).
     */
    @Select("SELECT * FROM harness_message WHERE user_id = #{userId} AND created_at > #{since} ORDER BY id ASC LIMIT #{limit}")
    List<MessageEntity> findByUserIdAndCreatedAtAfter(@Param("userId") Long userId,
                                                      @Param("since") LocalDateTime since,
                                                      @Param("limit") int limit);

    /**
     * Fetch the most recent {@code limit} messages of a session, ordered ascending by id
     * (newest {@code limit} rows, returned in chronological order).
     */
    @Select("""
            SELECT * FROM (
                SELECT * FROM harness_message
                WHERE user_id = #{userId} AND session_id = #{sessionId}
                ORDER BY id DESC LIMIT #{limit}
            ) t ORDER BY id ASC
            """)
    List<MessageEntity> findRecentByUserIdAndSessionId(@Param("userId") Long userId,
                                                       @Param("sessionId") String sessionId,
                                                       @Param("limit") int limit);
}
