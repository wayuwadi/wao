package cn.wayuwa.wao.config;

import cn.wayuwa.wao.service.AttendanceSyncScheduler;
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

/** 全局调度关闭时，部署可显式启用已授权成员的考勤同步。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wao.attendance.scheduling-enabled", havingValue = "true")
@ConditionalOnExpression("!${wao.scheduling.enabled:true}")
public class AttendanceSchedulingConfiguration {
    @Bean
    public ThreadPoolTaskScheduler attendanceTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("attendance-sync-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    public ScheduledFuture<?> attendanceSyncSchedule(
            @Qualifier("attendanceTaskScheduler") ThreadPoolTaskScheduler scheduler,
            AttendanceSyncScheduler attendance,
            @Value("${wao.attendance.initial-delay:PT10S}") String initialDelayValue,
            @Value("${wao.attendance.sync-delay:PT15M}") String syncDelayValue) {
        Duration initialDelay = Duration.parse(initialDelayValue);
        Duration syncDelay = Duration.parse(syncDelayValue);
        if (initialDelay.isNegative() || syncDelay.isNegative() || syncDelay.isZero()) {
            throw new IllegalArgumentException("Attendance initial delay must be nonnegative and sync delay must be positive");
        }
        return scheduler.scheduleWithFixedDelay(attendance::syncToday,
                Instant.now().plus(initialDelay), syncDelay);
    }
}
