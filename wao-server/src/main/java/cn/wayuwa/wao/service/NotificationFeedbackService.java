package cn.wayuwa.wao.service;

import java.time.LocalDateTime;

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
    private final FeishuNotificationFeedbackMapper mapper;
    private final AutomationJobMapper jobs;
    private final FeishuNotificationMapper notifications;
    public NotificationFeedbackService(FeishuNotificationFeedbackMapper mapper, AutomationJobMapper jobs, FeishuNotificationMapper notifications) { this.mapper = mapper; this.jobs = jobs; this.notifications = notifications; }
    public void collect(JsonNode event) {
        JsonNode action = event.path("action");
        if (!"automation-feedback".equals(action.path("value").path("kind").asText())) return;
        String notificationId = action.path("value").path("notificationId").asText();
        String outcome = action.path("value").path("action").asText();
        String openId = event.path("operator").path("open_id").asText();
        if (!StringUtils.hasText(notificationId) || !StringUtils.hasText(openId) || !("done".equals(outcome) || "missed".equals(outcome))) return;
        FeishuNotificationEntity p = notifications.selectById(notificationId);
        if (p == null) return; // 未知卡片不接受，避免伪造数据。
        FeishuNotificationFeedbackEntity row = mapper.selectOne(new LambdaQueryWrapper<FeishuNotificationFeedbackEntity>()
                .eq(FeishuNotificationFeedbackEntity::getNotificationId, notificationId).eq(FeishuNotificationFeedbackEntity::getOperatorOpenId, openId));
        if (row == null) { row = new FeishuNotificationFeedbackEntity(); row.setId("f" + System.currentTimeMillis()); row.setNotificationId(notificationId); row.setJobId(p.getJobId()); row.setNotificationAt(p.getSentAt()); row.setOperatorOpenId(openId); }
        row.setAction(outcome); row.setOperatorName(event.path("operator").path("user_id").asText(openId)); row.setActedAt(LocalDateTime.now());
        if (mapper.selectById(row.getId()) == null) mapper.insert(row); else mapper.updateById(row);
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
