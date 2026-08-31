package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 从 AI 控制台一步创建并派发的自主目标。 */
public record LaunchAutonomousGoalRequest(
        @NotBlank String goal,
        @NotBlank String projectId,
        String runnerId,
        Boolean planMode,
        Boolean goalMode,
        String reasoningEffort) {
}
