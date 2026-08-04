package cn.wayuwa.wao.dto;

/** 一台执行电脑最近上报的安全诊断日志快照。 */
public record AiRunnerLogDto(String runnerId, String runnerName, String content, String updatedAt) {
}
