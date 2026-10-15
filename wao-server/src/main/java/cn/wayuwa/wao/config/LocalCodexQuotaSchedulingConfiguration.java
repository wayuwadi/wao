package cn.wayuwa.wao.config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import cn.wayuwa.wao.service.LocalCodexQuotaService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 全局调度关闭时，仅为显式启用的本机额度采集保留分钟级后台刷新。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wao.codex-quota.enabled", havingValue = "true")
@ConditionalOnExpression("!${wao.scheduling.enabled:true}")
public class LocalCodexQuotaSchedulingConfiguration {

    /** 独立单线程采集，不启用其他服务的注解定时任务。 */
    @Bean
    public ThreadPoolTaskScheduler localCodexQuotaTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("local-codex-quota-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** 与常规调度沿用十秒启动延迟和一分钟周期，采集器继续统一节流。 */
    @Bean
    public ScheduledFuture<?> localCodexQuotaSchedule(
            ThreadPoolTaskScheduler localCodexQuotaTaskScheduler, LocalCodexQuotaService quotas) {
        return localCodexQuotaTaskScheduler.scheduleWithFixedDelay(quotas::refresh,
                Instant.now().plusSeconds(10), Duration.ofSeconds(60));
    }
}
