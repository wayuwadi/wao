package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import cn.wayuwa.wao.domain.FeishuNotificationFeedbackEntity;
import cn.wayuwa.wao.mapper.FeishuNotificationFeedbackMapper;
import cn.wayuwa.wao.mapper.AutomationJobMapper;
import cn.wayuwa.wao.mapper.FeishuNotificationMapper;
import cn.wayuwa.wao.domain.FeishuNotificationEntity;
import cn.wayuwa.wao.dto.NotificationFeedbackReportDto;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 接收飞书卡片回调并保存用户实际点击；无点击时不落库即为空反馈。 */
@Service
public class NotificationFeedbackService {
    private static final Logger log = LoggerFactory.getLogger(NotificationFeedbackService.class);
    private final FeishuNotificationFeedbackMapper mapper;
    private final AutomationJobMapper jobs;
    private final FeishuNotificationMapper notifications;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;

    public NotificationFeedbackService(FeishuNotificationFeedbackMapper mapper, AutomationJobMapper jobs,
            FeishuNotificationMapper notifications, SettingService settings, FeishuOpenApiClient feishu) {
        this.mapper = mapper; this.jobs = jobs; this.notifications = notifications;
        this.settings = settings; this.feishu = feishu;
    }

    /** 已落库的实际选择，以及共享反馈是否成功显示。 */
    public record Result(String outcome, boolean displayed) { }

    /**
     * 保存真实按钮回传并刷新所有人的最新选择。通知行锁覆盖保存与外部显示，避免多节点交叉覆盖。
     * 展示失败保留实际反馈，让同一人再次点击重试；未知或身份不匹配的回传返回 null。
     */
    @Transactional
    public Result collect(JsonNode event) {
        JsonNode value = event.path("action").path("value");
        if (!"automation-feedback".equals(value.path("kind").asText())) return null;
        String notificationId = value.path("notificationId").asText();
        String outcome = value.path("action").asText();
        String openId = event.path("operator").path("open_id").asText();
        String messageId = event.path("context").path("open_message_id").asText();
        if (!StringUtils.hasText(notificationId) || !openId.matches("ou_[A-Za-z0-9_-]+")
                || !("done".equals(outcome) || "missed".equals(outcome))) return null;
        FeishuNotificationEntity notification = notifications.lockById(notificationId);
        if (notification == null) return null;
        if (StringUtils.hasText(messageId) && StringUtils.hasText(notification.getMessageId())
                && !messageId.equals(notification.getMessageId())
                && !messageId.equals(notification.getFeedbackMessageId())) return null;
        FeishuNotificationFeedbackEntity row = mapper.selectOne(new LambdaQueryWrapper<FeishuNotificationFeedbackEntity>()
                .eq(FeishuNotificationFeedbackEntity::getNotificationId, notificationId)
                .eq(FeishuNotificationFeedbackEntity::getOperatorOpenId, openId));
        boolean isNew = row == null;
        if (isNew) {
            row = new FeishuNotificationFeedbackEntity(); row.setId("f" + UUID.randomUUID().toString().replace("-", ""));
            row.setNotificationId(notificationId); row.setJobId(notification.getJobId());
            row.setNotificationAt(notification.getSentAt()); row.setOperatorOpenId(openId);
        }
        row.setAction(outcome); row.setOperatorName(event.path("operator").path("user_id").asText(openId));
        row.setActedAt(LocalDateTime.now());
        if (isNew) mapper.insert(row); else mapper.updateById(row);
        boolean displayed = false;
        try {
            displayed = display(notification, messageId);
        } catch (RuntimeException ex) {
            // 飞书显示失败不能撤销用户已经提交的真实选择。
            log.warn("飞书通知 {} 反馈已保存，但实名摘要更新失败：{}", notificationId, ex.getMessage());
        }
        return new Result("done".equals(outcome) ? "做到了" : "没做到", displayed);
    }

    private boolean display(FeishuNotificationEntity notification, String sourceMessageId) {
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        if (!StringUtils.hasText(sourceMessageId) || !StringUtils.hasText(appId) || !StringUtils.hasText(secret)) return false;
        var rows = mapper.selectList(new LambdaQueryWrapper<FeishuNotificationFeedbackEntity>()
                .eq(FeishuNotificationFeedbackEntity::getNotificationId, notification.getId())
                .orderByAsc(FeishuNotificationFeedbackEntity::getOperatorOpenId));
        String summary = rows.stream()
                .filter(row -> row.getOperatorOpenId() != null && row.getOperatorOpenId().matches("ou_[A-Za-z0-9_-]+"))
                .map(row -> ("done".equals(row.getAction()) ? "✅ " : "❌ ")
                        + "<at id=" + row.getOperatorOpenId() + "></at>："
                        + ("done".equals(row.getAction()) ? "做到了" : "没做到"))
                .collect(Collectors.joining("\n"));
        String token = feishu.tenantAccessToken(appId, secret);
        if (notification.getCardTitle() != null) {
            feishu.updateFeedbackCard(token, notification.getMessageId(), feishu.feedbackCard(
                    notification.getCardTitle(), notification.getCardBody(), notification.getId(), summary));
        } else {
            var job = jobs.selectById(notification.getJobId());
            var card = feishu.feedbackCard(job == null ? "通知反馈" : job.getName() + " · 成员反馈",
                    "每人保留最新选择，再次点击可修改。", notification.getId(), summary);
            if (!StringUtils.hasText(notification.getFeedbackMessageId())) {
                String replyId = feishu.replyFeedbackCard(token, sourceMessageId, card, "feedback-" + notification.getId());
                notification.setMessageId(sourceMessageId);
                notification.setFeedbackMessageId(replyId);
                notifications.updateById(notification);
            } else {
                feishu.updateFeedbackCard(token, notification.getFeedbackMessageId(), card);
            }
        }
        return true;
    }

    /** 按日、周、月或全部范围汇总真实点击反馈。 */
    public NotificationFeedbackReportDto report(String period) {
        String safe = java.util.Set.of("day", "week", "month", "all").contains(period) ? period : "day";
        LocalDateTime from = switch (safe) {
            case "week" -> LocalDateTime.now().minusWeeks(1);
            case "month" -> LocalDateTime.now().minusMonths(1);
            case "all" -> LocalDateTime.of(2000, 1, 1, 0, 0);
            default -> LocalDateTime.now().toLocalDate().atStartOfDay();
        };
        var items = mapper.selectList(new LambdaQueryWrapper<FeishuNotificationFeedbackEntity>()
                .ge(FeishuNotificationFeedbackEntity::getNotificationAt, from)
                .orderByDesc(FeishuNotificationFeedbackEntity::getActedAt));
        int done = (int) items.stream().filter(x -> "done".equals(x.getAction())).count();
        int missed = items.size() - done;
        return new NotificationFeedbackReportDto(safe, done, missed, items.size(), items.stream().map(x -> {
            var job = jobs.selectById(x.getJobId());
            return new NotificationFeedbackReportDto.Row(String.valueOf(x.getNotificationAt()),
                    job == null ? x.getJobId() : job.getName(), x.getOperatorName(), "done".equals(x.getAction()) ? "做到了" : "没做到");
        }).toList());
    }
}
