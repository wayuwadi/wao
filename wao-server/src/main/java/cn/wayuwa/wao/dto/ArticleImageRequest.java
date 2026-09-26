package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 一次基于当前正文的总览图生成请求；画面偏好由成员自行保存。 */
public record ArticleImageRequest(
        @NotBlank @Size(max = 60000) String articleText,
        @NotBlank @Size(max = 4000) String visualPrompt,
        @Size(max = 64) String model) {
    public ArticleImageRequest(String articleText, String visualPrompt) {
        this(articleText, visualPrompt, "gpt-6-luna");
    }
}
