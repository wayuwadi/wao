package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.FeishuQuickCollectAiRunTriggerEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 飞书飞机表情触发 AI Run 的持久幂等记录。 */
@Mapper
public interface FeishuQuickCollectAiRunTriggerMapper
        extends BaseMapper<FeishuQuickCollectAiRunTriggerEntity> { }
