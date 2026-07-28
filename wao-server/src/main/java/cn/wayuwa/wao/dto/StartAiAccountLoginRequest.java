package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 发起 Claude Code 或 Codex OAuth 登录，label 留空时用账号邮箱兜底。 */
public record StartAiAccountLoginRequest(
        @NotBlank @Pattern(regexp = "claude|codex") String provider,
        @Size(max = 128) String label) {
}
