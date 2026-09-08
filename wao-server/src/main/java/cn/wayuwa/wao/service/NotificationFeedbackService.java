package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.util.UUID;
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
    private final FeishuReactionAuthorizationService authorization;
    private final FeishuOpenApiClient feishu;

    public NotificationFeedbackService(FeishuNotificationFeedbackMapper mapper, AutomationJobMapper jobs,
            FeishuNotificationMapper notifications, FeishuReactionAuthorizationService authorization, FeishuOpenApiClient feishu) {
        this.mapper = mapper; this.jobs = jobs; this.notifications = notifications;
        this.authorization = authorization; this.feishu = feishu;
    }

    /** 已落库的实际选择，以及共享反馈是否成功显示。 */
    public record Result(String outcome, boolean displayed, String hint) {
        public Result(String outcome, boolean displayed) { this(outcome, displayed, "个人表情暂未显示，请重试或重新授权"); }
    }

    /**
     * 保存真实按钮回传并以本人身份贴表情。通知行锁串行处理，改选只处理本人的表情。
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
        try {
            if (!StringUtils.hasText(messageId)) return new Result(label(outcome), false);
            if (!StringUtils.hasText(notification.getMessageId())) {
                notification.setMessageId(messageId);
                notifications.updateById(notification);
            }
            String token = authorization.accessToken(openId);
            if (!StringUtils.hasText(token)) return new Result(label(outcome), false,
                    "请先在 WAO 设置 → 账号与登录中授权个人表情，再点击一次");
            String originalMessageId = notification.getMessageId();
            if (StringUtils.hasText(row.getReactionId()) && !outcome.equals(row.getReactionAction())) {
                feishu.deleteOwnFeedbackReaction(token, originalMessageId, row.getReactionId());
                row.setReactionId(null); row.setReactionAction(null); mapper.updateById(row);
            }
            String reactionId = feishu.addUserMessageReaction(token, originalMessageId,
                    "done".equals(outcome) ? "CheckMark" : "CrossMark", openId);
            row.setReactionId(reactionId); row.setReactionAction(outcome); mapper.updateById(row);
            return new Result(label(outcome), true);
        } catch (RuntimeException ex) {
            log.warn("飞书通知 {} 反馈已保存，个人表情未显示，错误类型：{}", notificationId, ex.getClass().getSimpleName());
            return new Result(label(outcome), false);
        }
    }

    private static String label(String outcome) { return "done".equals(outcome) ? "做到了" : "没做到"; }

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
