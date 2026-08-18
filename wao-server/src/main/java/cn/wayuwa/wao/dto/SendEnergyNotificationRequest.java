package cn.wayuwa.wao.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 发送一次今日节奏精力补给群通知。动作结果由服务端读取成员当天记录，不接受前端自报次数。 */
public record SendEnergyNotificationRequest(
        @NotBlank(message = "精力补给动作不能为空")
        @Pattern(regexp = "WATER|MEDITATION", message = "精力补给动作不正确") String action,
        @NotNull(message = "记录日期不能为空") LocalDate date) {
}
