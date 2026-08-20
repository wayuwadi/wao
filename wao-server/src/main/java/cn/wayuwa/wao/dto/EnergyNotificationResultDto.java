package cn.wayuwa.wao.dto;

/** 精力补给通知结果；关闭通知或关闭对应动作时 sent 为 false，chatName 为实际目标显示名。 */
public record EnergyNotificationResultDto(boolean sent, String chatName) {
}
