package uct8086.ai.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import uct8086.ai.auth.entity.UserEntity;

/**
 * MyBatis-Plus mapper for {@link UserEntity}.
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
