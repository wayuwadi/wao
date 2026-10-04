package cn.wayuwa.wao.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 按成员保存和读取小记润色对照，读取时排除已删除小记。 */
@Mapper
public interface NotePolishVersionMapper {
    @Select("SELECT id FROM doc WHERE id = #{docId} FOR UPDATE")
    String lockDoc(@Param("docId") String docId);

    @Select("SELECT v.doc_id, v.member_id, v.source_markdown, v.polished_markdown "
            + "FROM note_polish_version v JOIN doc d ON d.id = v.doc_id "
            + "WHERE v.member_id = #{memberId} AND d.kb_id = 'kb-notes' AND d.deleted_at IS NULL")
    List<Version> listOwned(@Param("memberId") String memberId);

    /** 公开分享只读取仍属于文档作者的阅读结果；来源有效性由分享服务再次核对。 */
    @Select("SELECT v.doc_id, v.member_id, v.source_markdown, v.polished_markdown "
            + "FROM note_polish_version v JOIN doc d ON d.id = v.doc_id "
            + "WHERE v.doc_id = #{docId} AND v.member_id = d.author_id "
            + "AND d.kb_id = 'kb-notes' AND d.deleted_at IS NULL")
    Version findShareVersion(@Param("docId") String docId);

    @Insert("INSERT INTO note_polish_version (doc_id, member_id, source_markdown, polished_markdown) "
            + "VALUES (#{docId}, #{memberId}, #{sourceMarkdown}, #{polishedMarkdown}) "
            + "ON DUPLICATE KEY UPDATE source_markdown = VALUES(source_markdown), "
            + "polished_markdown = VALUES(polished_markdown), updated_at = CURRENT_TIMESTAMP(6)")
    void upsert(Version version);

    /** 只有服务端核实实际润色运行后才记录来源，旧客户端写入不继承旧验证标识。 */
    @Update("UPDATE note_polish_version SET verified_ai_job_id=#{jobId},source_body_fingerprint=#{sourceHash},verified_output_fingerprint=#{outputHash},source_rev=#{rev},verified_at=IF(#{jobId} IS NULL,NULL,CURRENT_TIMESTAMP(6)) WHERE doc_id=#{docId} AND member_id=#{memberId}")
    void markVerified(@Param("docId") String docId,@Param("memberId") String memberId,@Param("jobId") String jobId,
            @Param("sourceHash") String sourceHash,@Param("outputHash") String outputHash,@Param("rev") Integer rev);

    record Version(String docId, String memberId, String sourceMarkdown, String polishedMarkdown) { }
}
