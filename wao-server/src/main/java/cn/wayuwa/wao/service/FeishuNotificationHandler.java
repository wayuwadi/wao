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
    @org.springframework.beans.factory.annotation.Autowired
    private InternalNotificationService internalNotifications;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final FeishuNotificationMapper notifications;
    private final NotificationSettingsService notificationSettings;
    public FeishuNotificationHandler(SettingService settings, FeishuOpenApiClient feishu,
            FeishuNotificationMapper notifications, NotificationSettingsService notificationSettings) {
        this.settings = settings; this.feishu = feishu; this.notifications = notifications;
        this.notificationSettings = notificationSettings;
    }
    @Override public String name() { return NAME; }
    @Override public String execute() { throw new IllegalStateException("通知任务缺少任务上下文"); }
    @Override public String execute(AutomationJobEntity job) {
        if (!notificationSettings.allowsReminders()) {
            return "已跳过 · " + notificationSettings.blockedReason();
        }
        String notificationId = "n" + java.util.UUID.randomUUID().toString().replace("-", "");
        String content = StringUtils.hasText(job.getDescription()) ? job.getDescription() : job.getName();
        LocalDateTime now = LocalDateTime.now();
        internalNotifications.publish(cn.wayuwa.wao.feishu.FeishuWebhookNotifier.Card.simple(job.getName(), "blue", content));
        String messageId = "wao:" + notificationId;
        FeishuNotificationEntity sent = new FeishuNotificationEntity(); sent.setId(notificationId); sent.setJobId(job.getId()); sent.setSentAt(now);
        sent.setCardTitle(job.getName()); sent.setCardBody(content); sent.setMessageId(messageId);
        notifications.insert(sent);
        return "成功 · 已保存 WAO 内部通知";
    }
}
