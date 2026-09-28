package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.DocEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 小记挖掘任务、内容产物与来源快照的持久化。 */
@Mapper
public interface NoteMiningMapper {
    @Select("SELECT id FROM member WHERE id=#{memberId} FOR UPDATE")
    String lockMember(String memberId);

    @Select("""
            SELECT d.id,d.title,d.content_markdown,d.blocks_json,d.rev,d.updated_at
              FROM doc d JOIN kb k ON k.id=d.kb_id
             WHERE k.notes=1 AND k.deleted_at IS NULL AND d.deleted_at IS NULL AND d.archived_at IS NULL
               AND d.author_id=#{memberId} AND COALESCE(d.auto,0)=0
             ORDER BY COALESCE(d.updated_at,d.created_at) DESC LIMIT #{limit}
            """)
    List<DocEntity> recentNotes(@Param("memberId") String memberId, @Param("limit") int limit);

    @Select("""
            SELECT id,member_id,source_snapshot_json,source_count,ai_run_id,materialized_at,error_message,created_at
              FROM note_mining_job WHERE id=#{id} AND member_id=#{memberId}
            """)
    Job findOwnedJob(@Param("id") String id, @Param("memberId") String memberId);

    @Select("""
            SELECT id,member_id,source_snapshot_json,source_count,ai_run_id,materialized_at,error_message,created_at
              FROM note_mining_job WHERE id=#{id} AND member_id=#{memberId} FOR UPDATE
            """)
    Job lockOwnedJob(@Param("id") String id, @Param("memberId") String memberId);

    @Select("""
            SELECT id,member_id,source_snapshot_json,source_count,ai_run_id,materialized_at,error_message,created_at
              FROM note_mining_job WHERE member_id=#{memberId} ORDER BY created_at DESC LIMIT 1
            """)
    Job latestJob(@Param("memberId") String memberId);

    @Select("""
            SELECT id,member_id,source_snapshot_json,source_count,ai_run_id,materialized_at,error_message,created_at
              FROM note_mining_job WHERE materialized_at IS NULL AND error_message IS NULL
             ORDER BY created_at LIMIT #{limit}
            """)
    List<Job> pendingJobs(@Param("limit") int limit);

    @Insert("""
            INSERT INTO note_mining_job(id,member_id,source_snapshot_json,source_count,ai_run_id)
            VALUES(#{id},#{memberId},#{sourceSnapshotJson},#{sourceCount},#{aiRunId})
            """)
    void insertJob(Job job);

    @Update("UPDATE note_mining_job SET materialized_at=CURRENT_TIMESTAMP(6),error_message=NULL WHERE id=#{id} AND materialized_at IS NULL")
    int markMaterialized(String id);

    @Update("UPDATE note_mining_job SET error_message=#{message} WHERE id=#{id} AND materialized_at IS NULL")
    int markError(@Param("id") String id, @Param("message") String message);

    @Insert("""
            INSERT INTO note_mining_artifact(id,job_id,member_id,artifact_type,content_text,rationale_text)
            VALUES(#{id},#{jobId},#{memberId},#{type},#{content},#{rationale})
            """)
    void insertArtifact(Artifact artifact);

    @Insert("""
            INSERT INTO note_mining_artifact_source(artifact_id,doc_id,doc_rev,doc_title,excerpt,source_ord)
            VALUES(#{artifactId},#{docId},#{docRev},#{docTitle},#{excerpt},#{sourceOrd})
            """)
    void insertSource(Source source);

    @Select("""
            SELECT id,job_id,member_id,artifact_type AS type,content_text AS content,
                   rationale_text AS rationale,feedback_status,created_at,updated_at
              FROM note_mining_artifact WHERE job_id=#{jobId} AND member_id=#{memberId}
             ORDER BY FIELD(artifact_type,'INSIGHT','REMINDER','ARTICLE_SEED'),created_at
            """)
    List<Artifact> artifacts(@Param("jobId") String jobId, @Param("memberId") String memberId);

    @Select("""
            SELECT id,job_id,member_id,artifact_type AS type,content_text AS content,
                   rationale_text AS rationale,feedback_status,created_at,updated_at
              FROM note_mining_artifact WHERE id=#{id} AND member_id=#{memberId}
            """)
    Artifact artifactsForId(@Param("id") String id, @Param("memberId") String memberId);

    @Select("""
            SELECT artifact_id,doc_id,doc_rev,doc_title,excerpt,source_ord
              FROM note_mining_artifact_source WHERE artifact_id=#{artifactId} ORDER BY source_ord
            """)
    List<Source> sources(String artifactId);

    @Update("""
            UPDATE note_mining_artifact SET feedback_status=#{status},updated_at=CURRENT_TIMESTAMP(6)
             WHERE id=#{id} AND member_id=#{memberId}
            """)
    int updateFeedback(@Param("id") String id, @Param("memberId") String memberId, @Param("status") String status);

    record Job(String id, String memberId, String sourceSnapshotJson, int sourceCount, String aiRunId,
               LocalDateTime materializedAt, String errorMessage, LocalDateTime createdAt) { }
    record Artifact(String id, String jobId, String memberId, String type, String content, String rationale,
                    String feedbackStatus, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    record Source(String artifactId, String docId, int docRev, String docTitle, String excerpt, int sourceOrd) { }
}
