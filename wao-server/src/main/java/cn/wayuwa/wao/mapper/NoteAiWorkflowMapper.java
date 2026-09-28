package cn.wayuwa.wao.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 小记自动整理状态由服务端持久保存，按文档与成员双重约束读取。 */
@Mapper
public interface NoteAiWorkflowMapper {
    @Insert("""
            INSERT IGNORE INTO note_ai_workflow
              (doc_id,member_id,base_rev,original_title,source_markdown_hash,title_status,image_status,polish_status)
            VALUES (#{docId},#{memberId},#{baseRev},#{originalTitle},#{sourceMarkdownHash},
                    #{titleStatus},#{imageStatus},#{polishStatus})
            """)
    int insertIfAbsent(Workflow workflow);

    @Select("""
            SELECT doc_id,member_id,base_rev,original_title,source_markdown_hash,
                   title_status,title_job_id,image_status,image_run_id,polish_status,polish_job_id,
                   last_error,created_at,updated_at
              FROM note_ai_workflow WHERE doc_id=#{docId} FOR UPDATE
            """)
    Workflow lock(@Param("docId") String docId);

    @Select("""
            SELECT doc_id,member_id,base_rev,original_title,source_markdown_hash,
                   title_status,title_job_id,image_status,image_run_id,polish_status,polish_job_id,
                   last_error,created_at,updated_at
              FROM note_ai_workflow WHERE member_id=#{memberId} ORDER BY created_at DESC LIMIT 200
            """)
    List<Workflow> listOwned(@Param("memberId") String memberId);

    @Select("""
            SELECT doc_id FROM note_ai_workflow
             WHERE title_status IN ('PENDING','RUNNING') OR image_status IN ('PENDING','RUNNING')
                OR polish_status IN ('PENDING','RUNNING')
             ORDER BY updated_at LIMIT #{limit}
            """)
    List<String> pendingDocIds(@Param("limit") int limit);

    /** 统计成员已经入队、占用生成并发额度的小记 AI 阶段。 */
    @Select("""
            SELECT COALESCE(SUM(title_status='RUNNING') + SUM(image_status='RUNNING')
                         + SUM(polish_status='RUNNING'), 0)
              FROM note_ai_workflow WHERE member_id=#{memberId}
            """)
    int countRunningStages(@Param("memberId") String memberId);

    @Update("""
            UPDATE note_ai_workflow SET base_rev=#{baseRev}, original_title=#{originalTitle},
              source_markdown_hash=#{sourceMarkdownHash}, title_status=#{titleStatus},
              title_job_id=#{titleJobId}, image_status=#{imageStatus}, image_run_id=#{imageRunId},
              polish_status=#{polishStatus}, polish_job_id=#{polishJobId}, last_error=#{lastError},
              updated_at=CURRENT_TIMESTAMP(6)
             WHERE doc_id=#{docId} AND member_id=#{memberId}
            """)
    int update(Workflow workflow);

    record Workflow(String docId, String memberId, int baseRev, String originalTitle,
                    String sourceMarkdownHash, String titleStatus, String titleJobId,
                    String imageStatus, String imageRunId, String polishStatus, String polishJobId,
                    String lastError, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public Workflow withRev(int rev) {
            return new Workflow(docId, memberId, rev, originalTitle, sourceMarkdownHash,
                    titleStatus, titleJobId, imageStatus, imageRunId, polishStatus, polishJobId,
                    lastError, createdAt, updatedAt);
        }
        public Workflow withTitle(String status, String jobId, int rev, String title, String error) {
            return new Workflow(docId, memberId, rev, title, sourceMarkdownHash, status, jobId,
                    imageStatus, imageRunId, polishStatus, polishJobId, error, createdAt, updatedAt);
        }
        public Workflow withImage(String status, String runId, int rev, String hash, String error) {
            return new Workflow(docId, memberId, rev, originalTitle, hash, titleStatus, titleJobId,
                    status, runId, polishStatus, polishJobId, error, createdAt, updatedAt);
        }
        public Workflow withPolish(String status, String jobId, String error) {
            return new Workflow(docId, memberId, baseRev, originalTitle, sourceMarkdownHash,
                    titleStatus, titleJobId, imageStatus, imageRunId, status, jobId, error,
                    createdAt, updatedAt);
        }
    }
}
