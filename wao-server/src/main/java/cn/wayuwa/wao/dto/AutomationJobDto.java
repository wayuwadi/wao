package cn.wayuwa.wao.dto;

import java.util.List;

/** 自动化任务对外表示，字段与前端 {@code AutomationJob} 一致；runs 为 [time, result] 二元组数组。 */
public record AutomationJobDto(
        String id,
        String name,
        String icon,
        String schedule,
        String status,
        String last,
        String desc,
        String source,
        String target,
        List<String> steps,
        List<List<String>> runs) {
}
