package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 管理员保存的自主进化触发间隔与单次任务数。 */
public record SaveAiEvolutionScheduleRequest(
        @NotNull(message = "请选择自动执行间隔")
        @Min(value = 1, message = "自动执行间隔不能少于 1 小时")
        @Max(value = 168, message = "自动执行间隔不能超过 168 小时")
        Integer intervalHours,
        @NotNull(message = "请选择单次任务数")
        @Min(value = 1, message = "单次至少触发 1 个任务")
        @Max(value = 5, message = "单次最多触发 5 个任务")
        Integer batchSize) {
}
