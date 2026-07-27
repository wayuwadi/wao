package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 后台唤醒执行电脑所需的 Wake-on-LAN 网络参数。 */
public record UpdateRunnerWakeConfigRequest(
        @NotBlank @Size(max = 17) String mac,
        @NotBlank @Size(max = 255) String host,
        @Min(1) @Max(65535) Integer port) {
}
