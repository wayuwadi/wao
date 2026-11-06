package cn.wayuwa.wao.config;

import cn.wayuwa.wao.service.TaskTimeScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
@ConditionalOnExpression("${wao.scheduling.enabled:true} || ${wao.routines.scheduling-enabled:false}")
public class TaskTimeSchedulingConfiguration {
    @Bean public ThreadPoolTaskScheduler taskTimeTaskScheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("task-time-");scheduler.setRemoveOnCancelPolicy(true);return scheduler;}
    @Bean public ScheduledFuture<?> taskTimeSchedule(@Qualifier("taskTimeTaskScheduler") ThreadPoolTaskScheduler scheduler,TaskTimeScheduler tasks){return scheduler.scheduleWithFixedDelay(tasks::run,Instant.now().plusSeconds(10),Duration.ofSeconds(60));}
}
