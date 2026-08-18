package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/** 新建或修改家庭重要日的请求。 */
public record SaveFamilyImportantDateRequest(
        @NotBlank(message = "重要日名称不能为空") @Size(max = 64, message = "重要日名称最多 64 个字") String name,
        @NotNull(message = "请选择重要日日期") LocalDate eventDate,
        @Size(max = 200, message = "备注最多 200 个字") String note,
        boolean reminderEnabled,
        @Min(value = 0, message = "最多只能当天或提前提醒") @Max(value = 30, message = "最多提前 30 天提醒") int reminderDaysBefore,
        LocalTime reminderTime) { }
