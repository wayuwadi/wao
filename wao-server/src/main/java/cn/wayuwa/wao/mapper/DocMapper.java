package cn.wayuwa.wao.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.DocEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 文档表 Mapper。 */
@Mapper
public interface DocMapper extends BaseMapper<DocEntity> {

    /** 保存前固定文档锁顺序；成员发现信号随后在同一事务更新。 */
    @Select("SELECT * FROM doc WHERE id=#{id} FOR UPDATE")
    DocEntity selectByIdForUpdate(@Param("id") String id);

    /** 按主键只读有效文档的版本；不存在或已删除时返回 null，历史空版本视为 1。 */
    @Select("SELECT COALESCE(rev, 1) FROM doc WHERE id = #{id} AND deleted_at IS NULL")
    Integer selectAliveRevision(@Param("id") String id);

    /** 只返回真正引用目标的来源及摘要字段；同类型/id 必须在同一个出链对象内匹配。 */
    @Select("""
            SELECT id, title, blocks_json, content_markdown
            FROM doc
            WHERE deleted_at IS NULL AND id != #{id}
              AND JSON_CONTAINS(links_json, JSON_OBJECT('type', 'doc', 'id', #{id}))
            """)
    List<DocEntity> selectBacklinkSources(@Param("id") String id);

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
            "WHERE kb_id = #{kbId} AND deleted_at IS NULL AND archived_at IS NULL",
            "AND id IN",
            "<foreach collection='docs' item='doc' open='(' separator=',' close=')'>",
            "#{doc.id}",
            "</foreach>",
            "</script>"
    })
    int updateTreeOrders(@Param("kbId") String kbId, @Param("docs") List<DocEntity> docs);

    /** 目录移动仅改归属并递增版本，阻止旧正文快照恢复原文件夹；包含已归档文档。 */
    @Update("UPDATE doc SET folder_id = #{folderId}, rev = COALESCE(rev, 0) + 1 WHERE id = #{id} AND kb_id = #{kbId} AND deleted_at IS NULL")
    int moveDirectoryFolder(@Param("kbId") String kbId, @Param("id") String id, @Param("folderId") String folderId);

    /** 新客户端按正文 revision 原子保存；显式写 NULL，避免高级正文与旧块投影发生漂移。 */
    @Update("""
            UPDATE doc
               SET folder_id = #{doc.folderId}, title = #{doc.title}, blocks_json = #{doc.blocksJson},
                   content_json = #{doc.contentJson}, content_markdown = #{doc.contentMarkdown},
                   content_version = #{doc.contentVersion}, links_json = #{doc.linksJson}, tags_json = #{doc.tagsJson},
                   system_generated = #{doc.systemGenerated}, rev = #{doc.rev}, updated_at = #{doc.updatedAt}
             WHERE id = #{doc.id} AND deleted_at IS NULL AND rev = #{expectedRev}
            """)
    int updateIfRevision(@Param("doc") DocEntity doc, @Param("expectedRev") int expectedRev);

    /** 兼容无 expectedRev 的旧客户端与自动生成入口，同时显式写 NULL 清理过期高级快照。 */
    @Update("""
            UPDATE doc
               SET folder_id = #{doc.folderId}, title = #{doc.title}, author_id = #{doc.authorId}, auto = #{doc.auto},
                   blocks_json = #{doc.blocksJson}, content_json = #{doc.contentJson},
                   content_markdown = #{doc.contentMarkdown}, content_version = #{doc.contentVersion},
                   links_json = #{doc.linksJson}, tags_json = #{doc.tagsJson}, system_generated = #{doc.systemGenerated}, rev = #{doc.rev}, updated_at = #{doc.updatedAt},
                   deleted_at = #{doc.deletedAt}, deleted_by = #{doc.deletedBy}, cascade_del = #{doc.cascadeDel}
             WHERE id = #{doc.id}
            """)
    int updateContentSnapshot(@Param("doc") DocEntity doc);

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

    /** 归档只写生命周期元数据，正文、目录位置与回收站状态都保持不变。 */
    @Update("""
            UPDATE doc SET archived_at = #{archivedAt}, archived_by = #{archivedBy}
             WHERE id = #{id} AND deleted_at IS NULL AND archived_at IS NULL
            """)
    int markArchived(@Param("id") String id, @Param("archivedAt") LocalDateTime archivedAt,
            @Param("archivedBy") String archivedBy);

    /** 取消归档时显式清空元数据，避免 ORM 的 NULL 更新策略漏写。 */
    @Update("""
            UPDATE doc SET archived_at = NULL, archived_by = NULL
             WHERE id = #{id} AND deleted_at IS NULL AND archived_at IS NOT NULL
            """)
    int clearArchived(@Param("id") String id);

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
                   content_json = NULL, content_markdown = NULL, content_version = NULL,
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

    /** 旧块客户端编辑已绑定归档时清掉高级快照，并提升同一全局版本。 */
    @Update("""
            UPDATE doc
               SET folder_id = #{folderId}, title = #{title}, blocks_json = #{blocksJson},
                   content_json = NULL, content_markdown = NULL, content_version = NULL,
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

    /** 高级正文客户端手动编辑已绑定归档，完整 JSON 与 Markdown 在同一条 CAS 中写入。 */
    @Update("""
            UPDATE doc
               SET folder_id = #{folderId}, title = #{title}, blocks_json = #{blocksJson},
                   content_json = #{contentJson}, content_markdown = #{contentMarkdown},
                   content_version = #{contentVersion},
                   links_json = #{linksJson}, rev = #{rev}, updated_at = CURRENT_TIMESTAMP(6),
                   auto_sync_version = #{nextVersion}
             WHERE id = #{id} AND deleted_at IS NULL
               AND auto_sync_scope = #{syncScope} AND auto_sync_date = #{syncDate}
               AND auto_sync_version = #{expectedVersion}
            """)
    int updateAutoArchiveManuallyRich(@Param("id") String id,
            @Param("folderId") String folderId, @Param("title") String title,
            @Param("blocksJson") String blocksJson, @Param("linksJson") String linksJson,
            @Param("contentJson") String contentJson, @Param("contentMarkdown") String contentMarkdown,
            @Param("contentVersion") Integer contentVersion,
            @Param("rev") int rev, @Param("syncScope") String syncScope,
            @Param("syncDate") LocalDate syncDate, @Param("expectedVersion") long expectedVersion,
            @Param("nextVersion") long nextVersion);
}
