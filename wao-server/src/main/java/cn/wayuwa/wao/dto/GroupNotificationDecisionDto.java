package cn.wayuwa.wao.dto;

/** 无需暴露通知配置明细的运行时群通知门禁结果。 */
public record GroupNotificationDecisionDto(boolean allowed, String reason) { }
