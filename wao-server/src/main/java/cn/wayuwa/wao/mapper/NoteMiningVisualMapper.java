package cn.wayuwa.wao.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 三视角图像候选与现有挖掘任务共用成员归属。 */
@Mapper
public interface NoteMiningVisualMapper {
    @Insert("""
            INSERT INTO note_mining_visual
              (job_id,member_id,lens,caption,rationale,source_indexes_json)
            VALUES (#{jobId},#{memberId},#{lens},#{caption},#{rationale},#{sourceIndexesJson})
            """)
    int insert(Visual visual);

    @Select("""
            SELECT job_id,member_id,lens,caption,rationale,source_indexes_json,image_run_id,image_url,
                   status,error_message,feedback_status,created_at,updated_at
              FROM note_mining_visual WHERE job_id=#{jobId} AND member_id=#{memberId}
             ORDER BY FIELD(lens,'PROGRESS','REFRAME','NEXT_STEP')
            """)
    List<Visual> list(@Param("jobId") String jobId, @Param("memberId") String memberId);

    @Select("""
            SELECT job_id,member_id,lens,caption,rationale,source_indexes_json,image_run_id,image_url,
                   status,error_message,feedback_status,created_at,updated_at
              FROM note_mining_visual WHERE job_id=#{jobId} AND lens=#{lens} FOR UPDATE
            """)
    Visual lock(@Param("jobId") String jobId, @Param("lens") String lens);

    @Select("""
            SELECT job_id,lens FROM note_mining_visual WHERE status IN ('PENDING','RUNNING')
             ORDER BY created_at,FIELD(lens,'PROGRESS','REFRAME','NEXT_STEP') LIMIT #{limit}
            """)
    List<Key> pending(@Param("limit") int limit);

    @Update("""
            UPDATE note_mining_visual SET status=#{status}, image_run_id=#{runId}, image_url=#{imageUrl},
              error_message=#{error}, updated_at=CURRENT_TIMESTAMP(6)
             WHERE job_id=#{jobId} AND member_id=#{memberId} AND lens=#{lens}
            """)
    int updateImage(@Param("jobId") String jobId, @Param("memberId") String memberId,
            @Param("lens") String lens, @Param("status") String status,
            @Param("runId") String runId, @Param("imageUrl") String imageUrl,
            @Param("error") String error);

    @Update("""
            UPDATE note_mining_visual SET feedback_status=#{feedback},updated_at=CURRENT_TIMESTAMP(6)
             WHERE job_id=#{jobId} AND lens=#{lens} AND member_id=#{memberId}
            """)
    int updateFeedback(@Param("jobId") String jobId, @Param("lens") String lens,
            @Param("memberId") String memberId, @Param("feedback") String feedback);

    record Key(String jobId, String lens) { }
    record Visual(String jobId, String memberId, String lens, String caption, String rationale,
                  String sourceIndexesJson, String imageRunId, String imageUrl, String status,
                  String errorMessage, String feedbackStatus, LocalDateTime createdAt,
                  LocalDateTime updatedAt) { }
}
