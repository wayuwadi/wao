package cn.wayuwa.wao.dto;

/** 唤醒包发送结果；发送成功不等于电脑已经启动，最终以 Runner 心跳为准。 */
public record WakeRunnerResultDto(String runnerId, String requestedAt, String message) {
}
