package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.WorkdayRhythmChecklistOccurrenceEntity;
import cn.wayuwa.wao.domain.WorkdayRhythmReminderDeliveryEntity;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmChecklistItemMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmChecklistOccurrenceMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmReminderDeliveryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 每日必做的私聊、群聊与 Webhook 后台提醒。 */
@Service
public class WorkdayRhythmReminderService {
    private final WorkdayRhythmChecklistItemMapper items;
    private final WorkdayRhythmChecklistOccurrenceMapper occurrences;
    private final WorkdayRhythmReminderDeliveryMapper deliveries;
    private final WorkdayRhythmDashboardService dashboard;
    private final AccountMapper accounts;
    private final AccountIdentityMapper identities;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final FeishuWebhookConfigService webhooks;
    private final FeishuWebhookNotifier webhookNotifier;
    private final FeishuAppLinks links;
    private final NotificationSettingsService notificationSettings;
    private AttendanceService attendance;

    public WorkdayRhythmReminderService(WorkdayRhythmChecklistItemMapper items,
            WorkdayRhythmChecklistOccurrenceMapper occurrences, WorkdayRhythmReminderDeliveryMapper deliveries,
            WorkdayRhythmDashboardService dashboard, AccountMapper accounts, AccountIdentityMapper identities,
            SettingService settings, FeishuOpenApiClient feishu, FeishuWebhookConfigService webhooks,
            FeishuWebhookNotifier webhookNotifier, FeishuAppLinks links,
            NotificationSettingsService notificationSettings) {
        this.items=items; this.occurrences=occurrences; this.deliveries=deliveries; this.dashboard=dashboard;
        this.accounts=accounts; this.identities=identities; this.settings=settings; this.feishu=feishu;
        this.webhooks=webhooks; this.webhookNotifier=webhookNotifier; this.links=links;
        this.notificationSettings=notificationSettings;
    }

    /** 每分钟固化今天和前一天的实例，仅向到点且未完成的项目投递。 */
    public void sendDue(LocalDateTime now) {
        for (String memberId : items.reminderMembers()) {
            dashboard.dashboard(memberId, now.toLocalDate());
        }
        List<WorkdayRhythmChecklistOccurrenceEntity> candidates = occurrences.selectList(
                new LambdaQueryWrapper<WorkdayRhythmChecklistOccurrenceEntity>()
                        .isNull(WorkdayRhythmChecklistOccurrenceEntity::getCompletedAt)
                        .isNotNull(WorkdayRhythmChecklistOccurrenceEntity::getReminderTime)
                        .between(WorkdayRhythmChecklistOccurrenceEntity::getOccurrenceDate,
                                now.toLocalDate().minusDays(1), now.toLocalDate()));
        List<WorkdayRhythmChecklistOccurrenceEntity> due = candidates.stream()
                .filter(value -> !reminderAt(value).isAfter(now)).toList();
        if (attendance != null) due.stream().filter(value -> Set.of("CLOCK_IN", "CLOCK_OUT").contains(value.getKind()))
                .map(value -> value.getMemberId() + "\0" + value.getOccurrenceDate() + "\0" + reminderAt(value))
                .distinct().forEach(key -> {
                    String[] parts=key.split("\0");
                    attendance.refreshBeforeReminder(parts[0], LocalDate.parse(parts[1]), LocalDateTime.parse(parts[2]));
                });
        due.forEach(value -> {
            WorkdayRhythmChecklistOccurrenceEntity fresh=attendance!=null&&Set.of("CLOCK_IN","CLOCK_OUT").contains(value.getKind())
                    ? occurrences.findOwned(value.getId(),value.getMemberId()) : value;
            if(fresh==null||fresh.getCompletedAt()!=null) return;
            if (Boolean.TRUE.equals(fresh.getNotifyDirect())) deliver(fresh, "DIRECT", now);
            if (Boolean.TRUE.equals(fresh.getNotifyChat())) deliver(fresh, "CHAT", now);
            if (Boolean.TRUE.equals(fresh.getNotifyWebhook())) deliver(fresh, "WEBHOOK", now);
        });
    }

    @Autowired(required=false)
    void setAttendance(AttendanceService attendance) { this.attendance=attendance; }

    private void deliver(WorkdayRhythmChecklistOccurrenceEntity occurrence, String channel, LocalDateTime now) {
        if (!"DIRECT".equals(channel) && !notificationSettings.allowsReminders()) {
            skip(occurrence, channel, now, notificationSettings.blockedReason());
            return;
        }
        WorkdayRhythmReminderDeliveryEntity delivery = deliveries.selectOne(
                new LambdaQueryWrapper<WorkdayRhythmReminderDeliveryEntity>()
                        .eq(WorkdayRhythmReminderDeliveryEntity::getOccurrenceId, occurrence.getId())
                        .eq(WorkdayRhythmReminderDeliveryEntity::getChannel, channel).last("LIMIT 1"));
        if (delivery == null) {
            delivery = new WorkdayRhythmReminderDeliveryEntity(); delivery.setId(id());
            delivery.setOccurrenceId(occurrence.getId()); delivery.setChannel(channel); delivery.setStatus("PROCESSING");
            delivery.setAttempts(1); delivery.setAttemptedAt(now);
            try { deliveries.insert(delivery); } catch (DuplicateKeyException ignored) { return; }
        } else {
            if ("SENT".equals(delivery.getStatus()) || delivery.getAttempts() >= 6
                    || !delivery.getAttemptedAt().isBefore(now.minusMinutes(5))) return;
            int attempt = delivery.getAttempts() + 1;
            if (deliveries.claimRetry(delivery.getId(), delivery.getAttemptedAt(), now, attempt) == 0) return;
            delivery.setAttempts(attempt); delivery.setAttemptedAt(now); delivery.setStatus("PROCESSING");
        }
        try {
            send(occurrence, channel);
            delivery.setStatus("SENT"); delivery.setSentAt(now); delivery.setErrorMessage(null);
        } catch (RuntimeException exception) {
            delivery.setStatus("FAILED"); String message = exception.getMessage() == null ? "提醒发送失败" : exception.getMessage();
            delivery.setErrorMessage(message.substring(0, Math.min(512, message.length())));
        }
        deliveries.updateById(delivery);
    }

    private void skip(WorkdayRhythmChecklistOccurrenceEntity occurrence, String channel,
            LocalDateTime now, String reason) {
        WorkdayRhythmReminderDeliveryEntity existing = deliveries.selectOne(
                new LambdaQueryWrapper<WorkdayRhythmReminderDeliveryEntity>()
                        .eq(WorkdayRhythmReminderDeliveryEntity::getOccurrenceId, occurrence.getId())
                        .eq(WorkdayRhythmReminderDeliveryEntity::getChannel, channel)
                        .last("LIMIT 1"));
        if (existing != null) return;
        WorkdayRhythmReminderDeliveryEntity skipped = new WorkdayRhythmReminderDeliveryEntity();
        skipped.setId(id()); skipped.setOccurrenceId(occurrence.getId()); skipped.setChannel(channel);
        skipped.setStatus("SKIPPED"); skipped.setAttempts(0); skipped.setAttemptedAt(now);
        skipped.setErrorMessage(reason.substring(0, Math.min(512, reason.length())));
        try { deliveries.insert(skipped); } catch (DuplicateKeyException ignored) { }
    }

    private void send(WorkdayRhythmChecklistOccurrenceEntity value, String channel) {
        String title = "今日必做 · " + value.getTitle();
        String body = "这件事还没有在「今日节奏」中完成，截止时间为 "
                + value.getOccurrenceDate().plusDays(Boolean.TRUE.equals(value.getDueNextDay()) ? 1 : 0)
                + " " + value.getDueTime().toString().substring(0, 5) + "。";
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        if ("DIRECT".equals(channel)) {
            var account = accounts.findByMemberId(value.getMemberId());
            var identity = account == null ? null : identities.findByAccountProvider(account.getId(), "feishu");
            if (identity == null || !StringUtils.hasText(appId) || !StringUtils.hasText(secret)) throw new IllegalStateException("当前成员未绑定可用的飞书私聊");
            feishu.sendDirectCard(feishu.tenantAccessToken(appId, secret), identity.getProviderSubject(), title,
                    body, links.open("/workday-rhythm"), "打开今日节奏"); return;
        }
        FeishuWebhookNotifier.Card card = new FeishuWebhookNotifier.Card(title, "orange",
                List.of(List.of("归属日期", value.getOccurrenceDate().toString()),
                        List.of("截止时间", value.getDueTime().toString().substring(0, 5))),
                body, "打开今日节奏", links.open("/workday-rhythm"));
        if ("CHAT".equals(channel)) {
            if (!StringUtils.hasText(value.getChatId()) || !StringUtils.hasText(appId) || !StringUtils.hasText(secret)) throw new IllegalStateException("飞书群聊提醒配置不可用");
            feishu.sendCard(feishu.tenantAccessToken(appId, secret), value.getChatId(), card); return;
        }
        var target = webhooks.enabledTarget(value.getWebhookId()); webhookNotifier.send(target.url(), card);
    }

    private LocalDateTime reminderAt(WorkdayRhythmChecklistOccurrenceEntity value) {
        return value.getOccurrenceDate().plusDays(Boolean.TRUE.equals(value.getReminderNextDay()) ? 1 : 0)
                .atTime(value.getReminderTime());
    }
    private String id() { return "wrd" + UUID.randomUUID().toString().replace("-", "").substring(0, 29); }
}
