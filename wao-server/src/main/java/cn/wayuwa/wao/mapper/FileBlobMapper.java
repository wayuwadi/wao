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
    @Select("SELECT id, name, display_name, content_type, byte_size, created_by, project_id, created_at FROM file_blob ORDER BY created_at DESC, id DESC")
    List<FileBlobEntity> listMeta();

    @Select({"<script>",
            "SELECT id, name, display_name, content_type, byte_size, created_by, project_id, created_at FROM file_blob WHERE",
            "<choose><when test='projectId != null'>project_id = #{projectId}</when><otherwise>",
            "project_id IS NULL <if test='projectIds != null and !projectIds.isEmpty()'>OR project_id IN",
            "<foreach collection='projectIds' item='project' open='(' separator=',' close=')'>#{project}</foreach></if>",
            "</otherwise></choose> ORDER BY created_at DESC, id DESC", "</script>"})
    List<FileBlobEntity> listVisibleMeta(@Param("projectIds") java.util.Set<String> projectIds,
            @Param("projectId") String projectId);

    @Select("SELECT id, name, display_name, content_type, byte_size, created_by, project_id, "
            + "object_key, content_hash, created_at "
            + "FROM file_blob WHERE id = #{id}")
    FileBlobEntity findMeta(@Param("id") String id);

    @Update("UPDATE file_blob SET display_name = #{displayName} WHERE id = #{id}")
    int updateDisplayName(@Param("id") String id, @Param("displayName") String displayName);

    // 255 UTF-8 characters fit in 1020 bytes. Explicit width avoids H2's BINARY(1) default;
    // octet length also distinguishes trailing NUL bytes from binary padding.
    @Update("UPDATE file_blob SET display_name = #{displayName} WHERE id = #{id} AND "
            + "((display_name IS NULL AND #{expectedDisplayName} IS NULL) OR "
            + "(OCTET_LENGTH(display_name) = OCTET_LENGTH(#{expectedDisplayName}) AND "
            + "CAST(display_name AS BINARY(1020)) = CAST(#{expectedDisplayName} AS BINARY(1020))))")
    int compareAndSetDisplayName(@Param("id") String id, @Param("displayName") String displayName,
            @Param("expectedDisplayName") String expectedDisplayName);

    @Update("UPDATE file_blob SET name = #{name}, content_type = #{contentType}, "
            + "byte_size = #{byteSize}, content = #{content}, object_key = #{objectKey}, "
            + "content_hash = #{contentHash} WHERE id = #{id}")
    int replaceContent(@Param("id") String id, @Param("name") String name,
            @Param("contentType") String contentType, @Param("byteSize") long byteSize,
            @Param("content") byte[] content, @Param("objectKey") String objectKey,
            @Param("contentHash") String contentHash);
}
