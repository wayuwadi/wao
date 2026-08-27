package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.ChatReviewAutoConfigEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 自动聊天复盘配置与跨节点到期抢占。 */
@Mapper
public interface ChatReviewAutoConfigMapper extends BaseMapper<ChatReviewAutoConfigEntity> {

    /** 保存后从当前数据库时间重新计算下次触发点；停用时清空。 */
    @Update("""
            UPDATE chat_review_auto_config
               SET enabled = #{enabled}, interval_seconds = #{intervalSeconds},
                   range_kind = #{rangeKind}, range_value = #{rangeValue}, focus_text = #{focus},
                   ai_capability_id = #{aiCapabilityId}, updated_by = #{updatedBy},
                   next_fire_at = CASE WHEN #{enabled} THEN
                       DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL #{intervalSeconds} SECOND) ELSE NULL END,
                   updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = 1
            """)
    int save(@Param("enabled") boolean enabled, @Param("intervalSeconds") int intervalSeconds,
            @Param("rangeKind") String rangeKind, @Param("rangeValue") int rangeValue,
            @Param("focus") String focus, @Param("aiCapabilityId") String aiCapabilityId,
            @Param("updatedBy") String updatedBy);

    /** 多节点同时轮询时只允许一个节点推进同一个计划点。 */
    @Update("""
            UPDATE chat_review_auto_config
               SET next_fire_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL interval_seconds SECOND),
                   updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = 1 AND enabled = TRUE
               AND next_fire_at IS NOT NULL AND next_fire_at <= CURRENT_TIMESTAMP(6)
            """)
    int claimDue();

    /** 自动运行入队成功后记录真实运行 id。 */
    @Update("""
            UPDATE chat_review_auto_config
               SET last_run_id = #{runId}, updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = 1
            """)
    int recordRun(@Param("runId") String runId);
}
