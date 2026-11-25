package cn.wayuwa.wao.config;

import cn.wayuwa.wao.service.AgyQuotaService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 全局调度关闭时，仅为显式启用的本机 Agy 额度采集保留分钟级后台刷新。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wao.agy-quota.enabled", havingValue = "true")
@ConditionalOnExpression("!${wao.scheduling.enabled:true}")
public class LocalAgyQuotaSchedulingConfiguration {

    /** 独立单线程采集，不启用其他服务的注解定时任务。 */
    @Bean
    public ThreadPoolTaskScheduler localAgyQuotaTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("local-agy-quota-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** 与常规调度沿用 15 秒启动延迟和 1 分钟周期，采集器继续统一节流。 */
    @Bean
    public ScheduledFuture<?> localAgyQuotaSchedule(
            ThreadPoolTaskScheduler localAgyQuotaTaskScheduler, AgyQuotaService quotas) {
        return localAgyQuotaTaskScheduler.scheduleWithFixedDelay(quotas::refresh,
                Instant.now().plusSeconds(15), Duration.ofSeconds(60));
    }
}
