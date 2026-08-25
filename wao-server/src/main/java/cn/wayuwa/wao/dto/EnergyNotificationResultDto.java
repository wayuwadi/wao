package cn.wayuwa.wao.dto;

/** 今日行动通知结果；关闭通知或对应动作时 sent 为 false，chatName 为实际目标显示名。 */
public record EnergyNotificationResultDto(boolean sent, String chatName) {
}
