package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 保存文件与图床限制；最大值不能超过当前网关与 Spring multipart 基线。 */
public record SaveFileSettingsRequest(
        @Min(1) @Max(50) int fileMaxMb,
        @Min(1) @Max(10) int imageMaxMb,
        boolean downloadCaptchaEnabled) {
}
