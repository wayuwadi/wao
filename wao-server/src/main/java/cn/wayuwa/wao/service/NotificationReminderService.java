package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.AutomationJobDto;
import cn.wayuwa.wao.dto.CreateJobRequest;
import cn.wayuwa.wao.dto.NotificationReminderDto;
import cn.wayuwa.wao.dto.NotificationReminderSettingsDto;
import cn.wayuwa.wao.dto.PatchJobRequest;
import cn.wayuwa.wao.dto.SaveNotificationReminderRequest;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 在通知设置中维护每日飞书群提醒，底层复用真实自动化调度与飞书通知执行器。 */
@Service
public class NotificationReminderService {
    private static final String HANDLER = FeishuNotificationHandler.NAME;
    private static final String WORKDAY_HANDLER = WorkdayFeishuNotificationHandler.NAME;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final AutomationService automation;
    private final NotificationSettingsService notificationSettings;

    public NotificationReminderService(AutomationService automation,
            NotificationSettingsService notificationSettings) {
        this.automation = automation;
        this.notificationSettings = notificationSettings;
    }

    /** 返回全部每日群提醒；普通成员可查看，但不能增删改或试发。 */
    public NotificationReminderSettingsDto get(String memberId) {
        List<NotificationReminderDto> reminders = automation.listJobs().stream()
                .filter(job -> isReminderHandler(job.handler()))
                .map(this::toDto)
                .sorted(Comparator.comparing(NotificationReminderDto::time)
                        .thenComparing(NotificationReminderDto::id))
                .toList();
        return new NotificationReminderSettingsDto(
                notificationSettings.canManageSystem(memberId), reminders);
    }

    /** 新建一条按上海时间每日触发的群提醒。 */
    @Transactional
    public NotificationReminderDto create(String memberId, SaveNotificationReminderRequest request) {
        requireManager(memberId);
        NormalizedReminder value = normalize(request);
        AutomationJobDto created = automation.createJob(new CreateJobRequest(
                value.title(), "ri-alarm-line", "每日 " + value.time(), value.content(),
                "通知设置 · 每日提醒", "飞书群", List.of("按上海时间每日触发", "发送飞书提醒卡片"),
                cron(value.time()), HANDLER));
        if (!value.enabled()) created = automation.pauseJob(created.id());
        return toDto(created);
    }

    /** 完整保存已有每日提醒，并同步启停状态和下一次触发时间。 */
    @Transactional
    public NotificationReminderDto update(String memberId, String id,
            SaveNotificationReminderRequest request) {
        requireManager(memberId);
        AutomationJobDto current = requireReminder(id);
        NormalizedReminder value = normalize(request);
        boolean workdayOnly = WORKDAY_HANDLER.equals(current.handler());
        AutomationJobDto updated = automation.updateJob(id, new PatchJobRequest(
                value.title(), "ri-alarm-line", (workdayOnly ? "工作日 " : "每日 ") + value.time(), value.content(),
                "通知设置 · 每日提醒", "飞书群", workdayOnly
                        ? List.of("按上海时间每日检查工作日", "仅在工作日发送飞书提醒卡片")
                        : List.of("按上海时间每日触发", "发送飞书提醒卡片"),
                cron(value.time()), current.handler()));
        if (value.enabled() && "已暂停".equals(updated.status())) updated = automation.resumeJob(id);
        if (!value.enabled() && !"已暂停".equals(updated.status())) updated = automation.pauseJob(id);
        return toDto(updated);
    }

    /** 删除一条明确属于每日提醒配置的自动化任务及其运行记录。 */
    @Transactional
    public void delete(String memberId, String id) {
        requireManager(memberId);
        requireReminder(id);
        automation.deleteJob(id);
    }

    /** 管理员明确点击后立即试发一次，真实结果进入该提醒的运行记录。 */
    public NotificationReminderDto test(String memberId, String id) {
        requireManager(memberId);
        requireReminder(id);
        return toDto(automation.runJob(id, memberId));
    }

    private AutomationJobDto requireReminder(String id) {
        AutomationJobDto job;
        try {
            job = automation.getJob(id);
        } catch (NotFoundException exception) {
            throw new NotFoundException("每日提醒不存在：" + id);
        }
        if (!isReminderHandler(job.handler())) throw new NotFoundException("每日提醒不存在：" + id);
        return job;
    }

    private void requireManager(String memberId) {
        if (!notificationSettings.canManageSystem(memberId)) {
            throw new UnauthorizedException("只有管理员可以维护每日提醒");
        }
    }

    private NormalizedReminder normalize(SaveNotificationReminderRequest request) {
        if (request == null) throw new BadRequestException("提醒内容不能为空");
        String title = request.title() == null ? "" : request.title().trim();
        String content = request.content() == null ? "" : request.content().trim();
        if (!StringUtils.hasText(title)) throw new BadRequestException("提醒标题不能为空");
        if (title.length() > 128) throw new BadRequestException("提醒标题不能超过 128 个字符");
        if (!StringUtils.hasText(content)) throw new BadRequestException("提醒内容不能为空");
        if (content.length() > 512) throw new BadRequestException("提醒内容不能超过 512 个字符");
        try {
            String time = LocalTime.parse(request.time() == null ? "" : request.time().trim(), TIME).format(TIME);
            return new NormalizedReminder(title, content, time, request.enabled());
        } catch (DateTimeParseException exception) {
            throw new BadRequestException("提醒时间必须是 HH:mm 格式");
        }
    }

    private NotificationReminderDto toDto(AutomationJobDto job) {
        return new NotificationReminderDto(job.id(), job.name(), job.desc() == null ? "" : job.desc(),
                time(job.cronExpr()), !"已暂停".equals(job.status()), WORKDAY_HANDLER.equals(job.handler()),
                job.last(), job.nextFireAt());
    }

    private static boolean isReminderHandler(String handler) {
        return HANDLER.equals(handler) || WORKDAY_HANDLER.equals(handler);
    }

    private static String cron(String time) {
        LocalTime value = LocalTime.parse(time, TIME);
        return "0 " + value.getMinute() + " " + value.getHour() + " * * *";
    }

    private static String time(String cron) {
        String[] fields = cron == null ? new String[0] : cron.trim().split("\\s+");
        if (fields.length != 6 || !"0".equals(fields[0]) || !"*".equals(fields[3])
                || !"*".equals(fields[4]) || !"*".equals(fields[5])) {
            return "--:--";
        }
        try {
            return LocalTime.of(Integer.parseInt(fields[2]), Integer.parseInt(fields[1])).format(TIME);
        } catch (RuntimeException ignored) {
            return "--:--";
        }
    }

    private record NormalizedReminder(String title, String content, String time, boolean enabled) { }
}
