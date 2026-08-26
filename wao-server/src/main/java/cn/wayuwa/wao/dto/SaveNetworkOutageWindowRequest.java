package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 保存系统每天预期断网的时间窗口。 */
public record SaveNetworkOutageWindowRequest(
        @NotNull(message = "请选择是否启用断网时段") Boolean enabled,
        @NotNull(message = "请填写断网开始时间")
        @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d", message = "断网开始时间必须使用 HH:mm 格式")
        String startTime,
        @NotNull(message = "请填写断网结束时间")
        @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d", message = "断网结束时间必须使用 HH:mm 格式")
        String endTime) { }
