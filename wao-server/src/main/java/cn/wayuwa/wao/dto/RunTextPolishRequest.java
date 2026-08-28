package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 使用当前已保存提示词执行一次独立润色。 */
public record RunTextPolishRequest(
        @NotBlank @Size(max = 12000) String originalText) { }
