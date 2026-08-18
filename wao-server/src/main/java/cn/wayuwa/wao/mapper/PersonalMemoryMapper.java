package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.PersonalMemoryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PersonalMemoryMapper extends BaseMapper<PersonalMemoryEntity> {
    @Select("""
            SELECT id, member_id, title, content, category, capture_mode, media_type,
                   file_name, content_type, byte_size, file_content, created_at, updated_at
              FROM personal_memory
             WHERE id = #{id} AND member_id = #{memberId}
            """)
    PersonalMemoryEntity selectOwnedWithContent(@Param("id") String id, @Param("memberId") String memberId);
}
