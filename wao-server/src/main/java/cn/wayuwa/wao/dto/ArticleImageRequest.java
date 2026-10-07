package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import cn.wayuwa.wao.dto.DigitalMemberDtos.IdentitySelection;

/** 一次基于当前正文的总览图生成请求；画面偏好由成员自行保存。 */
public record ArticleImageRequest(
        @NotBlank @Size(max = 60000) String articleText,
        @NotBlank @Size(max = 4000) String visualPrompt,
        @Size(max = 64) String model,
        @Size(max = 32) String requestId,
        @Valid IdentitySelection identitySelection) {
    public ArticleImageRequest(String articleText, String visualPrompt, String model, String requestId) {
        this(articleText, visualPrompt, model, requestId, null);
    }
    public ArticleImageRequest(String articleText, String visualPrompt) {
        this(articleText, visualPrompt, "gpt-6-astra", null);
    }

    public ArticleImageRequest(String articleText, String visualPrompt, String model) {
        this(articleText, visualPrompt, model, null);
    }
}
