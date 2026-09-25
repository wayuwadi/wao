package cn.wayuwa.wao.mapper;

import java.util.List;
import cn.wayuwa.wao.domain.FileBlobEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileBlobMapper extends BaseMapper<FileBlobEntity> {
    @Select("SELECT id, name, display_name, content_type, byte_size, created_by, project_id, created_at FROM file_blob ORDER BY created_at DESC")
    List<FileBlobEntity> listMeta();

    @Select("SELECT id, name, display_name, content_type, byte_size, created_by, project_id, created_at "
            + "FROM file_blob WHERE id = #{id}")
    FileBlobEntity findMeta(@Param("id") String id);

    @Update("UPDATE file_blob SET display_name = #{displayName} WHERE id = #{id}")
    int updateDisplayName(@Param("id") String id, @Param("displayName") String displayName);
    @Update("UPDATE file_blob SET name = #{name}, content_type = #{contentType}, "
            + "byte_size = #{byteSize}, content = #{content}, object_key = #{objectKey}, "
            + "content_hash = #{contentHash} WHERE id = #{id}")
    int replaceContent(@Param("id") String id, @Param("name") String name,
            @Param("contentType") String contentType, @Param("byteSize") long byteSize,
            @Param("content") byte[] content, @Param("objectKey") String objectKey,
            @Param("contentHash") String contentHash);
}
