package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建自动化任务请求。cronExpr 为标准 5 段 Cron（分 时 日 月 周），非空时任务会按计划自动执行；
 * 留空则只能手动触发。
 */
public record CreateJobRequest(
        @NotBlank String name,
        String icon,
        String schedule,
        String desc,
        String source,
        String target,
        List<String> steps,
        String cronExpr) {
}
