package cn.wayuwa.wao.dto;

import java.util.List;

/** 局部更新自动化任务请求。空字段不改。 */
public record PatchJobRequest(
        String name,
        String icon,
        String schedule,
        String desc,
        String source,
        String target,
        List<String> steps,
        String cronExpr,
        String handler) {
}
