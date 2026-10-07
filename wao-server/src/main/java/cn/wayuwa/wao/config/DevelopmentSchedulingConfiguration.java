package cn.wayuwa.wao.config;

import cn.wayuwa.wao.service.DevelopmentDecisionService;
import cn.wayuwa.wao.service.DevelopmentWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 本机部署显式启用开发协调，不借此恢复全部生产调度。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="wao.development.worker-enabled",havingValue="true")
@ConditionalOnExpression("!${wao.scheduling.enabled:true}")
public class DevelopmentSchedulingConfiguration {
    /** 开发心跳、领取、通知分别调度，不阻塞业务收集。 */
    @Bean public ThreadPoolTaskScheduler developmentTaskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("development-coordinator-");scheduler.setRemoveOnCancelPolicy(true);return scheduler;
    }
    /** 全局调度关闭时只注册明确授权的开发能力。 */
    @Bean public List<ScheduledFuture<?>> developmentSchedules(ThreadPoolTaskScheduler developmentTaskScheduler,
            DevelopmentWorker worker,DevelopmentDecisionService decisions) {
        Instant now=Instant.now();
        return List.of(developmentTaskScheduler.scheduleWithFixedDelay(worker::heartbeat,now.plusSeconds(10),Duration.ofSeconds(20)),
            developmentTaskScheduler.scheduleWithFixedDelay(worker::tick,now.plusSeconds(15),Duration.ofSeconds(10)),
            developmentTaskScheduler.scheduleWithFixedDelay(decisions::deliver,now.plusSeconds(30),Duration.ofSeconds(30)));
    }
}
