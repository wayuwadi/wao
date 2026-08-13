package cn.wayuwa.wao.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟尝试抢占到期检查；实际检查间隔由数据库单例状态控制为十五分钟。 */
@Component
public class AiEvolutionScheduler {

    private final AiEvolutionService evolution;

    public AiEvolutionScheduler(AiEvolutionService evolution) {
        this.evolution = evolution;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void check() {
        evolution.checkAndLaunch();
    }
}
