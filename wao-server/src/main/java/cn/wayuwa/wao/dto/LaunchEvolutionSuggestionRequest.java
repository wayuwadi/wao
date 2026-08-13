package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 从首页建议清单选择工作流后立即派发。 */
public record LaunchEvolutionSuggestionRequest(@NotBlank(message = "请选择 AI 工作流") String projectId) {
}
