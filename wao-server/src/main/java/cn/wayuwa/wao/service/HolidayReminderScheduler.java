package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟投递到点的个人节日提醒，数据库唯一键负责多节点去重。 */
@Component
public class HolidayReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(HolidayReminderScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final HolidayCalendarService service;

    public HolidayReminderScheduler(HolidayCalendarService service) { this.service = service; }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void sendDueReminders() {
        try {
            service.sendDueReminders(LocalDateTime.now(ZONE));
        } catch (Exception exception) {
            log.error("节日提醒轮询失败", exception);
        }
    }
}
