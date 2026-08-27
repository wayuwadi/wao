package cn.wayuwa.wao.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.DocEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 文档表 Mapper。 */
@Mapper
public interface DocMapper extends BaseMapper<DocEntity> {

    /**
     * 用一条语句保存完整文档树顺序，只改文件夹和序号，不回写可能已被同步刷新的正文快照。
     *
     * @param kbId 知识库 id
     * @param docs 已完成完整性校验且带有目标文件夹和序号的文档
     * @return 匹配并更新的文档数
     */
    @Update({
            "<script>",
            "UPDATE doc",
            "SET folder_id = CASE id",
            "<foreach collection='docs' item='doc'>",
            "WHEN #{doc.id} THEN #{doc.folderId}",
            "</foreach>",
            "END,",
            "ord = CASE id",
            "<foreach collection='docs' item='doc'>",
            "WHEN #{doc.id} THEN #{doc.ord}",
            "</foreach>",
            "END",
            "WHERE kb_id = #{kbId} AND deleted_at IS NULL",
            "AND id IN",
            "<foreach collection='docs' item='doc' open='(' separator=',' close=')'>",
            "#{doc.id}",
            "</foreach>",
            "</script>"
    })
    int updateTreeOrders(@Param("kbId") String kbId, @Param("docs") List<DocEntity> docs);

    /** 软删除只改回收站元数据，不覆盖文档正文与归档版本。 */
    @Update("""
            UPDATE doc SET deleted_at = #{deletedAt}, deleted_by = #{deletedBy}, cascade_del = #{cascade}
             WHERE id = #{id}
            """)
    int markDeleted(@Param("id") String id, @Param("deletedAt") LocalDateTime deletedAt,
            @Param("deletedBy") String deletedBy, @Param("cascade") boolean cascade);

    /** 恢复只清除回收站元数据，不回写删除前读取的旧正文。 */
    @Update("""
            UPDATE doc SET deleted_at = NULL, deleted_by = NULL, cascade_del = 0
             WHERE id = #{id}
            """)
    int clearDeleted(@Param("id") String id);

    /** 新建自动文档后在同一事务中初始化稳定归档身份与版本。 */
    @Update("""
            UPDATE doc
               SET auto = 1, auto_sync_scope = #{syncScope}, auto_sync_date = #{syncDate},
                   auto_sync_version = #{syncVersion}
             WHERE id = #{id}
               AND auto_sync_scope IS NULL
               AND auto_sync_date IS NULL
            """)
    int initializeAutoArchive(@Param("id") String id, @Param("syncScope") String syncScope,
            @Param("syncDate") LocalDate syncDate, @Param("syncVersion") long syncVersion);

    /** 只有同一归档身份的更高版本能覆盖正文。 */
    @Update("""
            UPDATE doc
               SET folder_id = #{folderId}, title = #{title}, blocks_json = #{blocksJson}, auto = 1,
                   rev = rev + 1, auto_sync_scope = #{syncScope}, auto_sync_date = #{syncDate},
                   deleted_at = NULL, deleted_by = NULL, cascade_del = 0,
                   updated_at = CURRENT_TIMESTAMP(6), auto_sync_version = #{syncVersion}
             WHERE id = #{id} AND kb_id = #{kbId}
               AND auto_sync_scope = #{syncScope} AND auto_sync_date = #{syncDate}
               AND (auto_sync_version IS NULL OR auto_sync_version < #{syncVersion})
            """)
    int updateAutoArchive(@Param("id") String id, @Param("kbId") String kbId,
            @Param("folderId") String folderId,
            @Param("title") String title, @Param("blocksJson") String blocksJson,
            @Param("syncScope") String syncScope, @Param("syncDate") LocalDate syncDate,
            @Param("syncVersion") long syncVersion);

    /** 手动编辑已绑定的归档也要提升同一全局版本，旧节点无法绕过写栅。 */
    @Update("""
            UPDATE doc
               SET folder_id = #{folderId}, title = #{title}, blocks_json = #{blocksJson},
                   links_json = #{linksJson}, rev = #{rev}, updated_at = CURRENT_TIMESTAMP(6),
                   auto_sync_version = #{nextVersion}
             WHERE id = #{id} AND deleted_at IS NULL
               AND auto_sync_scope = #{syncScope} AND auto_sync_date = #{syncDate}
               AND auto_sync_version = #{expectedVersion}
            """)
    int updateAutoArchiveManually(@Param("id") String id,
            @Param("folderId") String folderId, @Param("title") String title,
            @Param("blocksJson") String blocksJson, @Param("linksJson") String linksJson,
            @Param("rev") int rev, @Param("syncScope") String syncScope,
            @Param("syncDate") LocalDate syncDate, @Param("expectedVersion") long expectedVersion,
            @Param("nextVersion") long nextVersion);
}
