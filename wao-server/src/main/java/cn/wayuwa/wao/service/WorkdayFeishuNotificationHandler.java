package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.AutomationJobEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/** 仅在中国工作日自动发送的飞书群提醒；管理员手动试发不受日期门禁影响。 */
@Component
public class WorkdayFeishuNotificationHandler implements JobHandler {
    public static final String NAME = "workday-feishu-notification";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final HolidayService holidays;
    private final FeishuNotificationHandler delegate;

    public WorkdayFeishuNotificationHandler(HolidayService holidays, FeishuNotificationHandler delegate) {
        this.holidays = holidays;
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String execute() {
        throw new IllegalStateException("通知任务缺少任务上下文");
    }

    @Override
    public String execute(AutomationJobEntity job, LocalDateTime scheduledFireAt) {
        if (scheduledFireAt == null) return delegate.execute(job);
        LocalDate date = scheduledFireAt.atZone(SHANGHAI).toLocalDate();
        if (!holidays.isWorkday(date)) return "已跳过 · " + date + " 为休息日";
        return delegate.execute(job);
    }
}
