package cn.wayuwa.wao.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟尝试抢占到期检查；实际检查间隔由数据库单例状态控制为十五分钟。 */
@Component
@ConditionalOnProperty(name = "wao.ai.evolution.scheduler-enabled", havingValue = "true")
public class AiEvolutionScheduler {

    private final AiEvolutionService evolution;

    public AiEvolutionScheduler(AiEvolutionService evolution) {
        this.evolution = evolution;
    }

    /** 尝试抢占一次到期检查；只有 Linux 主节点通过配置启用此调度器。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void check() {
        evolution.checkAndLaunch();
    }
}
