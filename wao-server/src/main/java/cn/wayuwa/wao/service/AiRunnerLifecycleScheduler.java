package cn.wayuwa.wao.service;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟检查一次 Runner 心跳，离线边沿由数据库条件更新保证多节点不重复通知。 */
public class AiRunnerLifecycleScheduler {

    private final AiRunnerLifecycleService lifecycle;

    public AiRunnerLifecycleScheduler(AiRunnerLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void detectOffline() {
        lifecycle.detectOffline(LocalDateTime.now());
    }
}
