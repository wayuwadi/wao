package cn.wayuwa.wao.dto;

import java.util.List;

/**
 * 自动化任务对外表示，字段与前端 {@code AutomationJob} 一致；runs 为 [time, result] 二元组数组。
 *
 * <p>{@code handler} 是服务端真实执行器名（如 {@code feishu-chat-sync}），前端据此决定要不要
 * 显示对应的配置入口；为空表示这条任务还只是描述性的模拟运行。
 */
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
        List<List<String>> runs,
        String handler) {
}
