package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 用一条或多条用户原始工作记录生成结构化周报。 */
public record GenerateWeeklyReportRequest(
        @NotBlank(message = "请先填写至少一条工作记录")
        @Size(max = 8000, message = "工作记录不能超过 8000 个字符")
        String records) {
}
