package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AppearancePhotoEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 形象实拍原件持久化。 */
@Mapper
public interface AppearancePhotoMapper extends BaseMapper<AppearancePhotoEntity> {
    /** 只按当前成员读取原件，知道其他成员 id 也不能越权。 */
    @Select("""
            SELECT id, member_id AS memberId, file_name AS fileName, content_type AS contentType,
                   byte_size AS byteSize, file_content AS fileContent, created_at AS createdAt
              FROM appearance_photo
             WHERE id = #{id} AND member_id = #{memberId}
            """)
    AppearancePhotoEntity selectOwnedWithContent(@Param("id") String id, @Param("memberId") String memberId);
}
