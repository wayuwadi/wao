package cn.wayuwa.wao.service;

import java.time.LocalDateTime;

import cn.wayuwa.wao.domain.AutomationJobEntity;
import cn.wayuwa.wao.domain.FeishuNotificationEntity;
import cn.wayuwa.wao.mapper.FeishuNotificationMapper;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 把自动化任务作为真实的飞书群提醒/公告发送，并携带可回传的反馈按钮。 */
@Component
public class FeishuNotificationHandler implements JobHandler {
    public static final String NAME = "feishu-notification";
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final FeishuNotificationMapper notifications;
    public FeishuNotificationHandler(SettingService settings, FeishuOpenApiClient feishu, FeishuNotificationMapper notifications) { this.settings = settings; this.feishu = feishu; this.notifications = notifications; }
    @Override public String name() { return NAME; }
    @Override public String execute() { throw new IllegalStateException("通知任务缺少任务上下文"); }
    @Override public String execute(AutomationJobEntity job) {
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        String chatId = settings.get(SettingService.CHAT_SYNC_CHAT_ID, "");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(secret) || !StringUtils.hasText(chatId)) {
            throw new IllegalStateException("请先在自动化的飞书群聊配置中填写应用凭据并选择目标群");
        }
        String notificationId = "n" + System.currentTimeMillis();
        String content = StringUtils.hasText(job.getDescription()) ? job.getDescription() : job.getName();
        LocalDateTime now = LocalDateTime.now();
        feishu.sendFeedbackCard(feishu.tenantAccessToken(appId, secret), chatId, job.getName(), content, notificationId);
        FeishuNotificationEntity sent = new FeishuNotificationEntity(); sent.setId(notificationId); sent.setJobId(job.getId()); sent.setSentAt(now); notifications.insert(sent);
        return "成功 · 已发送飞书通知";
    }
}
