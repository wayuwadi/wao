package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 管理员在后台修改执行电脑的最大并发线程数。 */
public record UpdateRunnerConcurrencyRequest(
        @NotNull @Min(1) @Max(8) Integer maxConcurrency) {
}
