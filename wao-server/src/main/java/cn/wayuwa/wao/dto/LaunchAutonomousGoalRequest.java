package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 从 AI 控制台一步创建并派发的自主目标。 */
public record LaunchAutonomousGoalRequest(
        @NotBlank @Size(max = 4000) String goal,
        @NotBlank String projectId) {
}
