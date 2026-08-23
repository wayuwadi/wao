package cn.wayuwa.wao.dto;
/** 保存大小周、每日文档与卦象模块设置。 */
public record SaveWorkdayRhythmSettingRequest(boolean followAlternateWeek, String documentKbId,
        String documentFolderId, String documentTemplateId, String documentTitlePattern,
        boolean tianrenEnabled, boolean tianrenRequired) { }
