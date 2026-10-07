package cn.wayuwa.wao.config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import cn.wayuwa.wao.service.FeishuBotInboxWorker;
import cn.wayuwa.wao.service.FeishuQuickCollectPollingScheduler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/** 全局调度关闭的部署可显式只启用飞书收集；默认本地实例仍不启动后台任务。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wao.feishu.collection-scheduling-enabled", havingValue = "true")
@ConditionalOnExpression("!${wao.scheduling.enabled:true}")
public class FeishuCollectionSchedulingConfiguration {

    /** 创建独立的两个工作线程，群 API 读取不会阻塞已接收消息的处理。 */
    @Bean
    public ThreadPoolTaskScheduler feishuCollectionTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("feishu-collection-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** 仅注册群轮询、收件处理及收件清理，沿用原有服务和调度周期。 */
    @Bean
    public List<ScheduledFuture<?>> feishuCollectionSchedules(
            ThreadPoolTaskScheduler feishuCollectionTaskScheduler,
            FeishuQuickCollectPollingScheduler polling, FeishuBotInboxWorker inbox,
            @Value("${wao.feishu.quick-collect-poll-ms:600000}") long pollMs) {
        if (pollMs < 1000) throw new IllegalArgumentException("飞书收集轮询周期至少为一秒");
        Instant now = Instant.now();
        return List.of(
                feishuCollectionTaskScheduler.scheduleWithFixedDelay(polling::poll,
                        now.plusSeconds(60), Duration.ofMillis(pollMs)),
                feishuCollectionTaskScheduler.scheduleWithFixedDelay(inbox::drain,
                        now.plusSeconds(10), Duration.ofSeconds(1)),
                feishuCollectionTaskScheduler.schedule(inbox::purge, new CronTrigger("0 20 4 * * *")));
    }
}
