package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟投递到点的个人行动提醒；投递表保证同计划同一天至多成功一次。 */
@Component
public class ActionReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ActionReminderScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ActionPlanService service;

    public ActionReminderScheduler(ActionPlanService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 35_000)
    public void sendDueReminders() {
        try {
            service.sendDueReminders(LocalDateTime.now(ZONE));
        } catch (Exception exception) {
            log.error("行动提醒轮询失败", exception);
        }
    }
}
