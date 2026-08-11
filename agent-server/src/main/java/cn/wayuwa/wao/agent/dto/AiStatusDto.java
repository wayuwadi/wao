package cn.wayuwa.wao.agent.dto;

/**
 * 文案生成模型的接入状态，未接入时前端要显式提示而不是静默不生成。
 */
public record AiStatusDto(boolean available, String provider, String model) {
}
