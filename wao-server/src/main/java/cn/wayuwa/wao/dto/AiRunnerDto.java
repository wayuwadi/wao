package cn.wayuwa.wao.dto;

/** 执行节点的安全展示信息，不包含节点令牌。 */
public record AiRunnerDto(
        String id,
        String name,
        String platform,
        int maxConcurrency,
        int activeRuns,
        boolean online,
        String lastSeenAt) {
}
