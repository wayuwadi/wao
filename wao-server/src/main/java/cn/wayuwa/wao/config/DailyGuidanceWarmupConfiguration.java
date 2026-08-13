package cn.wayuwa.wao.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 为每日建议的长耗时预热提供独立线程，避免阻塞其它 Spring 定时任务。 */
@Configuration
@ConditionalOnProperty(name = "wao.ai.daily-guidance-warmup-enabled", havingValue = "true")
public class DailyGuidanceWarmupConfiguration {

    /** 单线程执行成员预热；同一时刻不排队重复批次。 */
    @Bean(name = "dailyGuidanceWarmupExecutor")
    public ThreadPoolTaskExecutor dailyGuidanceWarmupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("daily-guidance-warmup-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
