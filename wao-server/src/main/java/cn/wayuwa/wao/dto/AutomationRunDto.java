package cn.wayuwa.wao.dto;

/** 自动化任务的一次运行记录；定时任务的执行人为「定时任务」。 */
public record AutomationRunDto(String time, String executor, String result) {
}
