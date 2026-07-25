package cn.wayuwa.wao.dto;

/** 节点完成 Codex 进程后的结果回传。 */
public record CompleteAiRunRequest(
        Integer exitCode,
        String resultSummary,
        String sessionId) {
}
