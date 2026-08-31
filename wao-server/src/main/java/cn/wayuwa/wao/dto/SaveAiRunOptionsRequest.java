package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 保存 AI Run 选择器中可由用户调整的全局默认项。 */
public record SaveAiRunOptionsRequest(
        @NotBlank String defaultExecutionMode) { }
