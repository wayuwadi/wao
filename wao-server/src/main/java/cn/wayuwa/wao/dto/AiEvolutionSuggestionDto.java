package cn.wayuwa.wao.dto;

/** 从真实 WAO 资产挖掘、可追溯并可直接派发的一条系统改进建议。 */
public record AiEvolutionSuggestionDto(
        String id,
        String title,
        String description,
        String icon,
        String goal,
        String sourceTitle,
        String sourceUrl) {
}
