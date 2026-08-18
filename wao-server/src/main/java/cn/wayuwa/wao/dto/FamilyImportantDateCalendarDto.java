package cn.wayuwa.wao.dto;

import java.util.List;

/** 家庭重要日维护列表、当前日期窗事件和真实飞书通道状态。 */
public record FamilyImportantDateCalendarDto(List<FamilyImportantDateDto> items,
        List<FamilyImportantDateEventDto> events, boolean reminderChannelAvailable,
        String reminderChannelHint) { }
