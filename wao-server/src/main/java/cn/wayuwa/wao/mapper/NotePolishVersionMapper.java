package cn.wayuwa.wao.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 按成员保存和读取小记润色对照，读取时排除已删除小记。 */
@Mapper
public interface NotePolishVersionMapper {
    @Select("SELECT id FROM doc WHERE id = #{docId} FOR UPDATE")
    String lockDoc(@Param("docId") String docId);

    @Select("SELECT v.doc_id, v.member_id, v.source_markdown, v.polished_markdown "
            + "FROM note_polish_version v JOIN doc d ON d.id = v.doc_id "
            + "WHERE v.member_id = #{memberId} AND d.kb_id = 'kb-notes' AND d.deleted_at IS NULL")
    List<Version> listOwned(@Param("memberId") String memberId);

    @Insert("INSERT INTO note_polish_version (doc_id, member_id, source_markdown, polished_markdown) "
            + "VALUES (#{docId}, #{memberId}, #{sourceMarkdown}, #{polishedMarkdown}) "
            + "ON DUPLICATE KEY UPDATE source_markdown = VALUES(source_markdown), "
            + "polished_markdown = VALUES(polished_markdown), updated_at = CURRENT_TIMESTAMP(6)")
    void upsert(Version version);

    record Version(String docId, String memberId, String sourceMarkdown, String polishedMarkdown) { }
}
