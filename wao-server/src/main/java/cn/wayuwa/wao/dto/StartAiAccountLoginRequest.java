package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 发起 OAuth 登录：目前只有 Claude Code 支持，label 留空时用账号邮箱兜底。 */
public record StartAiAccountLoginRequest(
        @NotBlank @Pattern(regexp = "claude") String provider,
        @Size(max = 128) String label) {
}
