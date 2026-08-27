package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/** 新建或修改日历提醒事项的请求。 */
public record SaveFamilyImportantDateRequest(
        @NotBlank(message = "重要日名称不能为空") @Size(max = 64, message = "重要日名称最多 64 个字") String name,
        @NotNull(message = "请选择重要日日期") LocalDate eventDate,
        @Size(max = 200, message = "备注最多 200 个字") String note,
        boolean reminderEnabled,
        @Min(value = 0, message = "最多只能当天或提前提醒") @Max(value = 30, message = "最多提前 30 天提醒") int reminderDaysBefore,
        LocalTime reminderTime,
        String calendarType,
        @Min(value = 1, message = "农历月份应为 1 至 12") @Max(value = 12, message = "农历月份应为 1 至 12") Integer lunarMonth,
        @Min(value = 1, message = "农历日期应为 1 至 30") @Max(value = 30, message = "农历日期应为 1 至 30") Integer lunarDay,
        boolean lunarLeapMonth,
        String notificationType,
        @Size(max = 128, message = "飞书群标识最多 128 个字") String chatId,
        @Size(max = 128, message = "飞书群名称最多 128 个字") String chatName) {

    /** 兼容既有只含公历和私聊字段的服务调用。 */
    public SaveFamilyImportantDateRequest(String name, LocalDate eventDate, String note,
            boolean reminderEnabled, int reminderDaysBefore, LocalTime reminderTime) {
        this(name, eventDate, note, reminderEnabled, reminderDaysBefore, reminderTime,
                "SOLAR", null, null, false, "DIRECT", null, null);
    }
}
