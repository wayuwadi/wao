package cn.wayuwa.wao.dto;

import java.util.List;

/** Runner 的一次定时记忆采集结果；失败时 reports 为空并携带已脱敏 error。 */
public record RunnerMemoryReportRequest(List<RunnerMemoryReportItem> reports, String error) { }

