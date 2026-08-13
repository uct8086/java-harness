package uct8086.ai.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import uct8086.ai.auth.entity.RoleEntity;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@link RoleEntity}.
 */
@Mapper
public interface RoleMapper extends BaseMapper<RoleEntity> {

    /**
     * Find role names for a given user id (via the auth_user_role join table).
     */
    @Select("""
            SELECT r.name
            FROM auth_role r
            JOIN auth_user_role ur ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
            """)
    List<String> findRoleNamesByUserId(Long userId);
}
