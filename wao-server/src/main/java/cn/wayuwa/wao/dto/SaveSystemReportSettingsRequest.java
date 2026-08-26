package cn.wayuwa.wao.dto;

import java.util.List;

/** 保存团队共享系统报告设置；服务层会校验时间、频率和枚举集合。 */
public record SaveSystemReportSettingsRequest(
        boolean automaticEnabled,
        String refreshTime,
        int refreshFrequencyHours,
        List<String> periods,
        List<String> aggregationOptions) { }
