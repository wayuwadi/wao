package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.ChatReviewRunEntity;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在所有应用节点上轮询聊天复盘，由数据库租约决定唯一执行者。 */
@Component
@ConditionalOnProperty(name = "wao.chat-review.processing-enabled", havingValue = "true", matchIfMissing = true)
public class ChatReviewScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChatReviewScheduler.class);

    private final ChatReviewService reviews;
    private final TaskExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final String workerToken = "chat-review-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

    public ChatReviewScheduler(ChatReviewService reviews,
            @Qualifier("chatReviewExecutor") TaskExecutor executor) {
        this.reviews = reviews;
        this.executor = executor;
    }

    /** 每两秒尝试推进一次持久化阶段，真正同步和 AI 等待都不占用 HTTP 请求。 */
    @Scheduled(fixedDelayString = "${wao.chat-review.poll-interval-ms:2000}",
            initialDelayString = "${wao.chat-review.initial-delay-ms:1000}")
    public void advance() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(this::runOne);
        } catch (RuntimeException exception) {
            running.set(false);
            log.warn("聊天复盘后台任务提交失败", exception);
        }
    }

    private void runOne() {
        ChatReviewRunEntity claimed = null;
        try {
            claimed = reviews.claimNext(workerToken);
            if (claimed != null) reviews.processClaimed(claimed.getId(), workerToken);
        } catch (RuntimeException exception) {
            log.error("聊天复盘后台编排异常", exception);
        } finally {
            if (claimed != null) {
                try {
                    reviews.releaseLease(claimed.getId(), workerToken);
                } catch (RuntimeException exception) {
                    log.warn("聊天复盘租约释放失败：{}", claimed.getId(), exception);
                }
            }
            running.set(false);
        }
    }
}
