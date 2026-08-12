package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.DrawingFileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 个人草图文件 Mapper；自定义 SQL 必须同时带上成员归属条件。 */
@Mapper
public interface DrawingFileMapper extends BaseMapper<DrawingFileEntity> {

    @Select("""
            SELECT id, owner_member_id, title, revision, created_at, updated_at
            FROM drawing_file
            WHERE owner_member_id = #{ownerMemberId}
            ORDER BY updated_at DESC, id DESC
            """)
    List<DrawingFileEntity> listOwned(@Param("ownerMemberId") String ownerMemberId);

    @Select("SELECT * FROM drawing_file WHERE id = #{id} AND owner_member_id = #{ownerMemberId} LIMIT 1")
    DrawingFileEntity findOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId);

    @Update("""
            UPDATE drawing_file
            SET scene_json = #{sceneJson}, revision = revision + 1, updated_at = #{updatedAt}
            WHERE id = #{id} AND owner_member_id = #{ownerMemberId} AND revision = #{expectedRevision}
            """)
    int saveScene(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId,
            @Param("sceneJson") String sceneJson, @Param("expectedRevision") int expectedRevision,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE drawing_file SET title = #{title}, updated_at = #{updatedAt}
            WHERE id = #{id} AND owner_member_id = #{ownerMemberId}
            """)
    int renameOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId,
            @Param("title") String title, @Param("updatedAt") LocalDateTime updatedAt);

    @Delete("DELETE FROM drawing_file WHERE id = #{id} AND owner_member_id = #{ownerMemberId}")
    int deleteOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId);
}
