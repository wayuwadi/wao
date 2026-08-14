package cn.wayuwa.wao.dto;

import java.util.List;

/** 当前成员的国际节日范围和真实提醒通道状态。 */
public record HolidaySettingsDto(
        List<String> selectedInternationalCategories,
        List<InternationalHolidayCategoryDto> availableInternationalCategories,
        boolean reminderChannelAvailable,
        String reminderChannelHint) { }
