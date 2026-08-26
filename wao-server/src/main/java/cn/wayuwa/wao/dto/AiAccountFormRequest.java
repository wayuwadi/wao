package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建/更新 AI 账号。
 *
 * <p>{@code credentials} 为 null 或空串时表示保留原有凭据；前端留空保存时沿用此语义。
 */
public record AiAccountFormRequest(
        @NotBlank @Pattern(regexp = "codex|claude|qoder|deepseek|model_gateway") String provider,
        @NotBlank @Size(max = 128) String label,
        @Size(max = 255) String email,
        @Size(max = 4000) String credentials,
        Boolean enabled,
        @Size(max = 512) String baseUrl,
        @Size(max = 128) String model,
        @Pattern(regexp = "responses|chat_completions") String apiProtocol,
        @Size(max = 4000) String apiKey) {

    public AiAccountFormRequest(String provider, String label, String email, String credentials, Boolean enabled) {
        this(provider, label, email, credentials, enabled, null, null, null, null);
    }
}
