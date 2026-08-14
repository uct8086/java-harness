package uct8086.ai.skills;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@link SkillEntity}.
 */
@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {

    @Select("SELECT * FROM harness_skill WHERE user_id = #{userId} ORDER BY name ASC")
    List<SkillEntity> findByUserIdOrderByNameAsc(@Param("userId") Long userId);
}
