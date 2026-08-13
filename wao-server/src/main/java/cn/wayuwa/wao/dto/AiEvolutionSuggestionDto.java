package cn.wayuwa.wao.dto;

/** 可直接派发给既有自主目标链路的一条系统改进建议。 */
public record AiEvolutionSuggestionDto(String id, String title, String description, String icon, String goal) {
}
