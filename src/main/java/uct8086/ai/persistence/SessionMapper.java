package uct8086.ai.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@link SessionEntity}.
 */
@Mapper
public interface SessionMapper extends BaseMapper<SessionEntity> {

    @Select("SELECT * FROM harness_session ORDER BY updated_at DESC")
    List<SessionEntity> findAllOrderByUpdatedAtDesc();
}
