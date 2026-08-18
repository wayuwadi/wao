package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟投递到点的家庭重要日提醒。 */
@Component
public class FamilyImportantDateReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(FamilyImportantDateReminderScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final FamilyImportantDateService service;

    public FamilyImportantDateReminderScheduler(FamilyImportantDateService service) { this.service = service; }

    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void sendDueReminders() {
        try {
            service.sendDueReminders(LocalDateTime.now(ZONE));
        } catch (Exception exception) {
            log.error("家庭重要日提醒轮询失败", exception);
        }
    }
}
