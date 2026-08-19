package cn.wayuwa.wao.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 聊天复盘使用独立单线程，不让多日飞书同步占住 Spring 定时器线程。 */
@Configuration
public class ChatReviewConfiguration {

    @Bean("chatReviewExecutor")
    public ThreadPoolTaskExecutor chatReviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("wao-chat-review-");
        executor.initialize();
        return executor;
    }
}
