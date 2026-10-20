package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.DrawingFileEntity;
import cn.wayuwa.wao.domain.DrawingFileVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 个人草图文件 Mapper；自定义 SQL 必须同时带上成员归属条件。 */
@Mapper
public interface DrawingFileMapper extends BaseMapper<DrawingFileEntity> {

    @Select("""
            SELECT f.id, f.owner_member_id, COALESCE(d.title, f.title) AS title, d.id AS doc_id, d.kb_id, f.revision, f.created_at, f.updated_at
            FROM drawing_file f LEFT JOIN doc d ON d.id = f.knowledge_doc_id
            WHERE f.owner_member_id = #{ownerMemberId} AND (f.knowledge_doc_id IS NULL OR (d.id IS NOT NULL AND d.deleted_at IS NULL AND d.archived_at IS NULL))
            ORDER BY f.updated_at DESC, f.id DESC
            """)
    List<DrawingFileEntity> listOwned(@Param("ownerMemberId") String ownerMemberId);

    @Select("SELECT f.id, f.owner_member_id, COALESCE(d.title, f.title) AS title, d.id AS doc_id, d.kb_id, f.scene_json, f.revision, f.created_at, f.updated_at FROM drawing_file f LEFT JOIN doc d ON d.id = f.knowledge_doc_id WHERE f.id = #{id} AND f.owner_member_id = #{ownerMemberId} AND (f.knowledge_doc_id IS NULL OR (d.id IS NOT NULL AND d.deleted_at IS NULL)) LIMIT 1")
    DrawingFileEntity findOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId);

    @Update("""
            UPDATE drawing_file
            SET scene_json = #{sceneJson}, revision = revision + 1, updated_at = #{updatedAt}
            WHERE id = #{id} AND owner_member_id = #{ownerMemberId} AND revision = #{expectedRevision}
            """)
    int saveScene(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId,
            @Param("sceneJson") String sceneJson, @Param("expectedRevision") int expectedRevision,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
            SELECT version.file_id, version.revision, version.created_at
            FROM drawing_file_version version
            INNER JOIN drawing_file file ON file.id = version.file_id
            WHERE version.file_id = #{id} AND file.owner_member_id = #{ownerMemberId}
            ORDER BY version.revision DESC
            LIMIT 3
            """)
    List<DrawingFileVersionEntity> listVersions(
            @Param("id") String id, @Param("ownerMemberId") String ownerMemberId);

    @Select("""
            SELECT version.*
            FROM drawing_file_version version
            INNER JOIN drawing_file file ON file.id = version.file_id
            WHERE version.file_id = #{id} AND version.revision = #{revision}
              AND file.owner_member_id = #{ownerMemberId}
            LIMIT 1
            """)
    DrawingFileVersionEntity findVersion(@Param("id") String id, @Param("revision") int revision,
            @Param("ownerMemberId") String ownerMemberId);

    @Insert("""
            INSERT INTO drawing_file_version (file_id, revision, scene_json, created_at)
            VALUES (#{fileId}, #{revision}, #{sceneJson}, #{createdAt})
            """)
    int insertVersion(@Param("fileId") String fileId, @Param("revision") int revision,
            @Param("sceneJson") String sceneJson, @Param("createdAt") LocalDateTime createdAt);

    @Delete("""
            DELETE FROM drawing_file_version
            WHERE file_id = #{fileId}
              AND revision NOT IN (
                SELECT revision FROM (
                  SELECT revision
                  FROM drawing_file_version
                  WHERE file_id = #{fileId}
                  ORDER BY revision DESC
                  LIMIT 3
                ) recent_versions
              )
            """)
    int pruneVersions(@Param("fileId") String fileId);

    @Update("""
            UPDATE drawing_file SET title = #{title}, updated_at = #{updatedAt}
            WHERE id = #{id} AND owner_member_id = #{ownerMemberId}
            """)
    int renameOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId,
            @Param("title") String title, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE drawing_file SET knowledge_doc_id = #{docId} WHERE id = #{id} AND owner_member_id = #{memberId} AND knowledge_doc_id IS NULL")
    int linkDocument(@Param("id") String id, @Param("docId") String docId, @Param("memberId") String memberId);

    @Select("SELECT COUNT(*) FROM drawing_file WHERE id = #{id} AND knowledge_doc_id IS NOT NULL")
    int linkedDocumentCount(@Param("id") String id);

    @Delete("DELETE FROM drawing_file WHERE id = #{id} AND owner_member_id = #{ownerMemberId}")
    int deleteOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId);
}
