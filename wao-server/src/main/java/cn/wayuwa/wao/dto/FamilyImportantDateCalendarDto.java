package cn.wayuwa.wao.dto;

import java.util.List;

/** 日历提醒事项、当前日期窗事件和真实飞书通道状态。 */
public record FamilyImportantDateCalendarDto(List<FamilyImportantDateDto> items,
        List<FamilyImportantDateEventDto> events, boolean reminderChannelAvailable,
        String reminderChannelHint, boolean groupChannelAvailable, String groupChannelHint) {

    /** 兼容既有只关心私聊通道的调用。 */
    public FamilyImportantDateCalendarDto(List<FamilyImportantDateDto> items,
            List<FamilyImportantDateEventDto> events, boolean reminderChannelAvailable,
            String reminderChannelHint) {
        this(items, events, reminderChannelAvailable, reminderChannelHint,
                reminderChannelAvailable, reminderChannelHint);
    }
}
