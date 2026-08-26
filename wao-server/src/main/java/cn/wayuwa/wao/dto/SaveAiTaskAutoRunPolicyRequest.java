package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 保存最早待办自动派发开关与空闲阈值。 */
public record SaveAiTaskAutoRunPolicyRequest(
        @NotNull(message = "请选择是否自动派发") Boolean enabled,
        @NotNull(message = "请填写空闲时长")
        @Min(value = 1, message = "空闲时长不能少于 1 小时")
        @Max(value = 168, message = "空闲时长不能超过 168 小时") Integer idleHours,
        String projectId,
        List<String> taskStatuses,
        String notificationWebhookId) {
}
