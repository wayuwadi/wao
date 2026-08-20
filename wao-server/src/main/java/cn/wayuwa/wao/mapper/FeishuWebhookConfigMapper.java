package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.FeishuWebhookConfigEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 通用飞书机器人 Webhook 配置 Mapper。 */
@Mapper
public interface FeishuWebhookConfigMapper extends BaseMapper<FeishuWebhookConfigEntity> {
}
