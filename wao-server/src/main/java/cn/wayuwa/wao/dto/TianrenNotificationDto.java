package cn.wayuwa.wao.dto;

/** 当前成员可见的卡片状态，不返回群标识、凭据或卡片快照。 */
public record TianrenNotificationDto(String state, String targetName, String message, boolean retryable) { }
