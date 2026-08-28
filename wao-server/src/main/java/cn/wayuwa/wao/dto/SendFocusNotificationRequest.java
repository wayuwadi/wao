package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** 发送当前成员已落库的今日重点；正文不从请求体接收。 */
public record SendFocusNotificationRequest(
        @NotNull(message = "计划日期不能为空") LocalDate date) {
}
