package uct8086.ai.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@link MemoryEntity}.
 */
@Mapper
public interface MemoryMapper extends BaseMapper<MemoryEntity> {

    @Select("SELECT * FROM harness_memory WHERE user_id = #{userId} ORDER BY created_at ASC")
    List<MemoryEntity> findByUserIdOrderByCreatedAtAsc(@Param("userId") Long userId);

    @Select("SELECT * FROM harness_memory WHERE user_id = #{userId} AND category = #{category} ORDER BY created_at ASC")
    List<MemoryEntity> findByUserIdAndCategoryOrderByCreatedAtAsc(@Param("userId") Long userId,
                                                                  @Param("category") String category);

    @Select("""
            SELECT * FROM harness_memory
            WHERE user_id = #{userId}
              AND (LOWER(content) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                   OR LOWER(category) LIKE CONCAT('%', LOWER(#{keyword}), '%'))
            ORDER BY created_at ASC
            """)
    List<MemoryEntity> searchByUserId(@Param("userId") Long userId, @Param("keyword") String keyword);
}
