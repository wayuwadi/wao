package cn.wayuwa.wao.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期性补齐最近一个已结束的周、月、年回顾。 */
@Component
@ConditionalOnProperty(name = "wao.ai.life-review-scheduler-enabled", havingValue = "true")
public class LifeReviewScheduler {
    private static final Logger log = LoggerFactory.getLogger(LifeReviewScheduler.class);
    private final LifeReviewService reviews;
    private final TaskExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public LifeReviewScheduler(LifeReviewService reviews,
            @Qualifier("lifeReviewExecutor") TaskExecutor executor) {
        this.reviews = reviews;
        this.executor = executor;
    }

    /** 启动十分钟后及此后每六小时检查；已有周期存档不会再次消耗模型。 */
    @Scheduled(fixedDelayString = "${wao.ai.life-review-interval-ms:21600000}",
            initialDelayString = "${wao.ai.life-review-initial-delay-ms:600000}")
    public void prepareClosedPeriods() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(this::runBatch);
        } catch (RuntimeException exception) {
            running.set(false);
            log.warn("周期人生回顾任务提交失败", exception);
        }
    }

    private void runBatch() {
        try {
            LifeReviewService.BatchResult result = reviews.prepareClosedPeriods();
            if (result.failed() > 0) {
                log.warn("周期人生回顾结束：成员 {}，生成 {}，跳过 {}，失败 {}，最后错误：{}",
                        result.members(), result.generated(), result.skipped(), result.failed(), result.lastError());
            } else if (result.generated() > 0) {
                log.info("周期人生回顾结束：成员 {}，生成 {}，跳过 {}",
                        result.members(), result.generated(), result.skipped());
            }
        } catch (RuntimeException exception) {
            log.error("周期人生回顾补齐失败", exception);
        } finally {
            running.set(false);
        }
    }
}
