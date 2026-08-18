package cn.wayuwa.wao.dto;

/** 精力补给群通知结果；关闭通知或关闭对应动作时 sent 为 false。 */
public record EnergyNotificationResultDto(boolean sent, String chatName) {
}
