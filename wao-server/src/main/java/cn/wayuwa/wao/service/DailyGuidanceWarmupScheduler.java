package cn.wayuwa.wao.service;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在成员打开首页前，周期性补齐当天缺失的情境建议。 */
@Component
@ConditionalOnProperty(name = "wao.ai.daily-guidance-warmup-enabled", havingValue = "true")
public class DailyGuidanceWarmupScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyGuidanceWarmupScheduler.class);

    private final DailyGuidanceService guidance;
    private final TaskExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public DailyGuidanceWarmupScheduler(DailyGuidanceService guidance,
            @Qualifier("dailyGuidanceWarmupExecutor") TaskExecutor executor) {
        this.guidance = guidance;
        this.executor = executor;
    }

    /** 每半小时检查一次缺失缓存；实际 Codex 等待放到独立线程，避免阻塞其它定时任务。 */
    @Scheduled(fixedDelayString = "${wao.ai.daily-guidance-warmup-interval-ms:1800000}",
            initialDelayString = "${wao.ai.daily-guidance-warmup-initial-delay-ms:120000}")
    public void prepareMissing() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(this::runBatch);
        } catch (RuntimeException ex) {
            running.set(false);
            log.warn("每日情境建议预热任务提交失败", ex);
        }
    }

    private void runBatch() {
        try {
            DailyGuidanceService.WarmupResult result = guidance.prepareMissingForAllMembers();
            if (result.failed() > 0) {
                log.warn("每日情境建议预热结束：成员 {}，已有 {}，生成 {}，失败 {}，最后错误：{}",
                        result.members(), result.cached(), result.generated(), result.failed(), result.lastError());
            } else if (result.generated() > 0) {
                log.info("每日情境建议预热结束：成员 {}，已有 {}，生成 {}",
                        result.members(), result.cached(), result.generated());
            }
        } catch (RuntimeException ex) {
            log.error("每日情境建议预热失败", ex);
        } finally {
            running.set(false);
        }
    }
}
