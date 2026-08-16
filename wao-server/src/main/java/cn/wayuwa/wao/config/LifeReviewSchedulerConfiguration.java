package cn.wayuwa.wao.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 为周期回顾的长耗时补齐任务提供独立单线程。 */
@Configuration
@ConditionalOnProperty(name = "wao.ai.life-review-scheduler-enabled", havingValue = "true")
public class LifeReviewSchedulerConfiguration {
    /** 串行生成成员回顾，防止周期边界瞬间占满本地 Codex Runner。 */
    @Bean(name = "lifeReviewExecutor")
    public ThreadPoolTaskExecutor lifeReviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("life-review-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
