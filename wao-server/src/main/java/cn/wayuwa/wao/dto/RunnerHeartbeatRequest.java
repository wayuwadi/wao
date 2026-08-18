package cn.wayuwa.wao.dto;

/** Runner 心跳正文；旧版节点发送空对象时所有字段保持为空。 */
public record RunnerHeartbeatRequest(
        String logTail,
        RunnerCodexQuotaReport codexQuota,
        String codexQuotaError) {
}
