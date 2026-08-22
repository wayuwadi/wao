package cn.wayuwa.wao.mapper;
import cn.wayuwa.wao.domain.IdeaResearchRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
@Mapper public interface IdeaResearchRunMapper extends BaseMapper<IdeaResearchRunEntity> {
    @Select("SELECT * FROM idea_research_run WHERE id=#{id} FOR UPDATE")
    IdeaResearchRunEntity findForUpdate(@Param("id") String id);
    @Update("""
            UPDATE idea_research_run SET status='RUNNING', runner_id=#{runnerId}, claim_token=#{claimToken},
              claimed_at=CURRENT_TIMESTAMP(6), updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=#{id} AND status='QUEUED'
            """)
    int claim(@Param("id") String id,@Param("runnerId") String runnerId,@Param("claimToken") String claimToken);
    @Update("""
            UPDATE idea_research_run SET status='SUCCEEDED', response_text=#{response}, error_text=NULL,
              prompt_text=NULL, claim_token=NULL, doc_id=#{docId}, finished_at=CURRENT_TIMESTAMP(6), updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=#{id} AND runner_id=#{runnerId} AND claim_token=#{claimToken} AND status='RUNNING'
            """)
    int completeSuccess(@Param("id") String id,@Param("runnerId") String runnerId,@Param("claimToken") String claimToken,
            @Param("response") String response,@Param("docId") String docId);
    @Update("""
            UPDATE idea_research_run SET status='FAILED', response_text=NULL, error_text=#{error},
              prompt_text=NULL, claim_token=NULL, finished_at=CURRENT_TIMESTAMP(6), updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=#{id} AND runner_id=#{runnerId} AND claim_token=#{claimToken} AND status='RUNNING'
            """)
    int completeFailure(@Param("id") String id,@Param("runnerId") String runnerId,@Param("claimToken") String claimToken,
            @Param("error") String error);
    @Update("""
            UPDATE idea_research_run SET status='FAILED', prompt_text=NULL, response_text=NULL,
              claim_token=NULL, error_text='调研执行超时，请重新调研',
              finished_at=CURRENT_TIMESTAMP(6), updated_at=CURRENT_TIMESTAMP(6)
            WHERE status='RUNNING' AND claimed_at < DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 70 MINUTE)
            """)
    int expireStale();
}
