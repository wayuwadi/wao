package cn.wayuwa.wao.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅由指定节点轮询；数据库状态仍提供跨节点原子抢占保护。 */
@ConditionalOnProperty(name = "wao.ai.task-auto-run.scheduler-enabled", havingValue = "true")
public class AiTaskAutoRunScheduler {

    private final AiTaskAutoRunService service;

    public AiTaskAutoRunScheduler(AiTaskAutoRunService service) {
        this.service = service;
    }

    /** 每分钟尝试一次，实际检查频率由数据库中的五分钟窗口限制。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void check() {
        service.checkAndLaunch();
    }
}
