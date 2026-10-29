package cn.wayuwa.wao.config;

import cn.wayuwa.wao.service.RoutineReminderScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 全局后台任务关闭时，部署可显式仅启用本人订阅的日期和习惯提醒。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wao.routines.scheduling-enabled", havingValue = "true")
@ConditionalOnExpression("!${wao.scheduling.enabled:true}")
public class RoutineSchedulingConfiguration {
    @Bean
    public ThreadPoolTaskScheduler routineReminderTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("routine-reminder-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    public ScheduledFuture<?> routineReminderSchedule(
            @Qualifier("routineReminderTaskScheduler") ThreadPoolTaskScheduler scheduler,
            RoutineReminderScheduler reminders) {
        return scheduler.scheduleWithFixedDelay(reminders::run,
                Instant.now().plusSeconds(10), Duration.ofSeconds(60));
    }
}
