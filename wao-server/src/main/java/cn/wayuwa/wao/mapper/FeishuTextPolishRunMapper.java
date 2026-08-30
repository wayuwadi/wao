package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.FeishuTextPolishRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 飞书润色运行的多节点原子认领与状态推进。 */
@Mapper
public interface FeishuTextPolishRunMapper extends BaseMapper<FeishuTextPolishRunEntity> {

    @Select("SELECT * FROM feishu_text_polish_run WHERE status IN "
            + "('QUEUED','AI_PENDING','GENERATED','DELIVERED') ORDER BY created_at ASC LIMIT 8")
    List<FeishuTextPolishRunEntity> findAdvanceable();

    @Update("UPDATE feishu_text_polish_run SET status='ENQUEUEING', updated_at=#{now} "
            + "WHERE id=#{id} AND status='QUEUED'")
    int claimQueued(@Param("id") String id, @Param("now") LocalDateTime now);

    @Update("UPDATE feishu_text_polish_run SET status='AI_PENDING', ai_run_id=#{aiRunId}, "
            + "updated_at=#{now} WHERE id=#{id} AND status='ENQUEUEING'")
    int linkAiRun(@Param("id") String id, @Param("aiRunId") String aiRunId,
            @Param("now") LocalDateTime now);

    @Update("UPDATE feishu_text_polish_run SET status='GENERATED', polished_text=#{polishedText}, "
            + "updated_at=#{now} WHERE id=#{id} AND status='AI_PENDING'")
    int markGenerated(@Param("id") String id, @Param("polishedText") String polishedText,
            @Param("now") LocalDateTime now);

    @Update("UPDATE feishu_text_polish_run SET status='SENDING', updated_at=#{now} "
            + "WHERE id=#{id} AND status='GENERATED'")
    int claimGenerated(@Param("id") String id, @Param("now") LocalDateTime now);

    @Update("UPDATE feishu_text_polish_run SET status='DELIVERED', updated_at=#{now} "
            + "WHERE id=#{id} AND status='SENDING'")
    int markDelivered(@Param("id") String id, @Param("now") LocalDateTime now);

    @Update("UPDATE feishu_text_polish_run SET status='RECORDING', updated_at=#{now} "
            + "WHERE id=#{id} AND status='DELIVERED'")
    int claimDelivered(@Param("id") String id, @Param("now") LocalDateTime now);

    @Update("UPDATE feishu_text_polish_run SET status='SUCCEEDED', error_message=NULL, "
            + "updated_at=#{now} WHERE id=#{id} AND status='RECORDING'")
    int markSucceeded(@Param("id") String id, @Param("now") LocalDateTime now);

    @Update("UPDATE feishu_text_polish_run SET status='FAILED', error_message=#{error}, "
            + "updated_at=#{now} WHERE id=#{id} AND status NOT IN ('SUCCEEDED','FAILED')")
    int markFailed(@Param("id") String id, @Param("error") String error,
            @Param("now") LocalDateTime now);

    @Update("UPDATE feishu_text_polish_run SET status='DELIVERED', updated_at=#{now} "
            + "WHERE status='RECORDING' AND updated_at < #{staleBefore}")
    int recoverStaleRecording(@Param("staleBefore") LocalDateTime staleBefore,
            @Param("now") LocalDateTime now);

    @Update("UPDATE feishu_text_polish_run SET status='FAILED', error_message='润色结果发送状态不确定，请重新发送原消息', "
            + "updated_at=#{now} WHERE status='SENDING' AND updated_at < #{staleBefore}")
    int failStaleSending(@Param("staleBefore") LocalDateTime staleBefore,
            @Param("now") LocalDateTime now);
}
