package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.FeishuTextPolishConfigDto;
import cn.wayuwa.wao.dto.FeishuWebhookEndpointDto;
import cn.wayuwa.wao.dto.SaveFeishuTextPolishConfigRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 管理群聊润色机器人的开关、精确触发名称和出站 Webhook。 */
@Service
public class FeishuTextPolishConfigService {
    public static final String DEFAULT_MENTION_NAME = "润色机器人";
    private static final int MENTION_NAME_LIMIT = 64;

    private final SettingService settings;
    private final FeishuWebhookConfigService webhooks;

    public FeishuTextPolishConfigService(SettingService settings, FeishuWebhookConfigService webhooks) {
        this.settings = settings;
        this.webhooks = webhooks;
    }

    /** 返回不包含 Webhook 地址的当前配置。 */
    public FeishuTextPolishConfigDto config() {
        String webhookId = settings.get(SettingService.FEISHU_TEXT_POLISH_WEBHOOK_ID, "");
        String webhookName = webhookId.isBlank() ? "" : webhooks.list().stream()
                .filter(item -> webhookId.equals(item.id()))
                .map(FeishuWebhookEndpointDto::name)
                .findFirst().orElse("");
        return new FeishuTextPolishConfigDto(
                settings.getBoolean(SettingService.FEISHU_TEXT_POLISH_ENABLED),
                webhookId,
                webhookName,
                settings.get(SettingService.FEISHU_TEXT_POLISH_MENTION_NAME, DEFAULT_MENTION_NAME));
    }

    /** 保存配置；Webhook 可选，未选择时由现有飞书应用直接回复原消息。 */
    @Transactional
    public FeishuTextPolishConfigDto save(SaveFeishuTextPolishConfigRequest request) {
        boolean enabled = request.enabled() == null
                ? settings.getBoolean(SettingService.FEISHU_TEXT_POLISH_ENABLED)
                : request.enabled();
        String webhookId = request.webhookId() == null
                ? settings.get(SettingService.FEISHU_TEXT_POLISH_WEBHOOK_ID, "")
                : request.webhookId().trim();
        String mentionName = request.mentionName() == null
                ? settings.get(SettingService.FEISHU_TEXT_POLISH_MENTION_NAME, DEFAULT_MENTION_NAME)
                : request.mentionName().trim();
        if (!StringUtils.hasText(mentionName)) {
            throw new BadRequestException("润色机器人触发名称不能为空");
        }
        if (mentionName.length() > MENTION_NAME_LIMIT) {
            throw new BadRequestException("润色机器人触发名称最多 64 个字符");
        }
        if (StringUtils.hasText(webhookId)) webhooks.enabledTarget(webhookId);
        settings.put(SettingService.FEISHU_TEXT_POLISH_ENABLED, String.valueOf(enabled));
        settings.put(SettingService.FEISHU_TEXT_POLISH_WEBHOOK_ID, webhookId);
        settings.put(SettingService.FEISHU_TEXT_POLISH_MENTION_NAME, mentionName);
        return config();
    }

    /** 快速判断轮询器是否需要读取群消息。 */
    public boolean enabled() {
        return settings.getBoolean(SettingService.FEISHU_TEXT_POLISH_ENABLED);
    }

    /** 精确触发名称，始终返回非空默认值。 */
    public String mentionName() {
        return settings.get(SettingService.FEISHU_TEXT_POLISH_MENTION_NAME, DEFAULT_MENTION_NAME);
    }

    /** 当前配置的稳定 Webhook id。 */
    public String webhookId() {
        return settings.get(SettingService.FEISHU_TEXT_POLISH_WEBHOOK_ID, "");
    }
}
