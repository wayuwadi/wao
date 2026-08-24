package cn.wayuwa.wao.dto;

/** Runner 执行期间轮询的控制信号。 */
public record AiRunControlDto(boolean cancelled, String continuationInput) {
}
