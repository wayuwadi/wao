package cn.wayuwa.wao.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 推进飞书润色的 AI、Webhook 交付和历史落库阶段。 */
@Component
public class FeishuTextPolishScheduler {
    private static final Logger log = LoggerFactory.getLogger(FeishuTextPolishScheduler.class);
    private final FeishuTextPolishService service;
    private final AtomicBoolean running = new AtomicBoolean();

    public FeishuTextPolishScheduler(FeishuTextPolishService service) {
        this.service = service;
    }

    /** 单节点串行推进，多节点由数据库状态 CAS 保证互斥。 */
    @Scheduled(
            fixedDelayString = "${wao.feishu.text-polish-worker-ms:1000}",
            initialDelayString = "${wao.feishu.text-polish-worker-initial-delay-ms:60000}")
    public void advance() {
        if (!running.compareAndSet(false, true)) return;
        try {
            service.advance();
        } catch (RuntimeException exception) {
            log.error("飞书润色调度失败", exception);
        } finally {
            running.set(false);
        }
    }
}
