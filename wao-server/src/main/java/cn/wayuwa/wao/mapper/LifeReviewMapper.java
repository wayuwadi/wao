package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.LifeReviewEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 人生回顾存档 Mapper。 */
@Mapper
public interface LifeReviewMapper extends BaseMapper<LifeReviewEntity> {
    /** 同一成员和自然周期只保留最新生成版本，避免定时补齐与手动刷新重复建档。 */
    @Insert("""
            INSERT INTO life_review
              (id, member_id, period_type, period_key, period_start, period_end, task_count,
               assistant_conversation_count, chat_day_count, sources_json, report_json, generated_at)
            VALUES
              (#{id}, #{memberId}, #{periodType}, #{periodKey}, #{periodStart}, #{periodEnd}, #{taskCount},
               #{assistantConversationCount}, #{chatDayCount}, #{sourcesJson}, #{reportJson}, #{generatedAt})
            ON DUPLICATE KEY UPDATE
              id = VALUES(id), period_start = VALUES(period_start), period_end = VALUES(period_end),
              task_count = VALUES(task_count), assistant_conversation_count = VALUES(assistant_conversation_count),
              chat_day_count = VALUES(chat_day_count), sources_json = VALUES(sources_json),
              report_json = VALUES(report_json), generated_at = VALUES(generated_at)
            """)
    void upsert(LifeReviewEntity entity);
}
