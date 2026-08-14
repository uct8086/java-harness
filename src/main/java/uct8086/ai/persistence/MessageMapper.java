package uct8086.ai.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@link MessageEntity}.
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

    @Select("SELECT * FROM harness_message WHERE user_id = #{userId} AND session_id = #{sessionId} ORDER BY created_at ASC")
    List<MessageEntity> findByUserIdAndSessionIdOrderByCreatedAtAsc(@Param("userId") Long userId,
                                                                    @Param("sessionId") String sessionId);
}
