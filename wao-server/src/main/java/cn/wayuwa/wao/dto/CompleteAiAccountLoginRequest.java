package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 回填授权码完成登录；{@code code} 接受 {@code code} 或回调页给出的 {@code code#state} 形式。 */
public record CompleteAiAccountLoginRequest(
        @NotBlank @Size(max = 64) String state,
        @NotBlank @Size(max = 1024) String code) {
}
