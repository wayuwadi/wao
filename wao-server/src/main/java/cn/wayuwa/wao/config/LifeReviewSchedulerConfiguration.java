package cn.wayuwa.wao.config;

import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 已退役的历史执行器实现，不再注册到应用上下文。 */
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
