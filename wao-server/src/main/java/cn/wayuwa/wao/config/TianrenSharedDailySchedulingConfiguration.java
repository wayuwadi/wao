package cn.wayuwa.wao.config;

import cn.wayuwa.wao.service.TianrenSharedDailyService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Opt in to the shared cast alone when a deployed profile disables global scheduling. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wao.tianren.shared-daily-scheduler-enabled", havingValue = "true")
@ConditionalOnExpression("!${wao.scheduling.enabled:true}")
public class TianrenSharedDailySchedulingConfiguration {
    @Bean
    public ThreadPoolTaskScheduler tianrenSharedDailyTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("tianren-shared-daily-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    public ScheduledFuture<?> tianrenSharedDailySchedule(
            @Qualifier("tianrenSharedDailyTaskScheduler") ThreadPoolTaskScheduler scheduler,
            TianrenSharedDailyService daily,
            @Value("${wao.tianren.shared-daily-initial-delay:PT10S}") String initialDelayValue,
            @Value("${wao.tianren.shared-daily-delay:PT1M}") String delayValue) {
        Duration initialDelay = Duration.parse(initialDelayValue);
        Duration delay = Duration.parse(delayValue);
        if (initialDelay.isNegative() || delay.isNegative() || delay.isZero()) {
            throw new IllegalArgumentException("Shared daily cast initial delay must be nonnegative and delay positive");
        }
        return scheduler.scheduleWithFixedDelay(daily::generateDue, Instant.now().plus(initialDelay), delay);
    }
}
