package cn.wayuwa.wao.dto;

import java.util.List;

/** 执行节点的安全展示信息，不包含节点令牌。 */
public record AiRunnerDto(
        String id,
        String name,
        String platform,
        List<String> engines,
        int maxConcurrency,
        Integer configuredMaxConcurrency,
        int activeRuns,
        boolean online,
        String lastSeenAt,
        String wakeMac,
        String wakeHost,
        int wakePort,
        boolean wakeConfigured,
        String lastWakeRequestedAt) {
}
