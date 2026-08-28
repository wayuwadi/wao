package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 保存当前成员的润色方法提示词。 */
public record SaveTextPolishPromptRequest(
        @NotBlank @Size(max = 4000) String prompt) { }
