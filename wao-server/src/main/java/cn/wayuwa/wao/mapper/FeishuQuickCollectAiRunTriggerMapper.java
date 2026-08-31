package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.FeishuQuickCollectAiRunTriggerEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 飞书飞机表情触发 AI Run 的持久幂等记录。 */
@Mapper
public interface FeishuQuickCollectAiRunTriggerMapper
        extends BaseMapper<FeishuQuickCollectAiRunTriggerEntity> {

    /** 已有任一持久派工记录后不再反复读取这条消息的表情。 */
    @Select("SELECT COUNT(*) FROM feishu_quick_collect_ai_run_trigger WHERE message_id = #{messageId}")
    long countByMessageId(@Param("messageId") String messageId);
}
