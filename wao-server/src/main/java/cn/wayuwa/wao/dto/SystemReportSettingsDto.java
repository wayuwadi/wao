package cn.wayuwa.wao.dto;

import java.util.List;

/** 团队共享的系统报告自动刷新与汇总范围设置。 */
public record SystemReportSettingsDto(
        boolean automaticEnabled,
        String refreshTime,
        int refreshFrequencyHours,
        List<String> periods,
        List<String> aggregationOptions) { }
