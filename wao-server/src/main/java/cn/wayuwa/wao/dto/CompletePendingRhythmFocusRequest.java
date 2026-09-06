package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 原文本用于检测历史条目已被编辑或移动，避免误完成别的事项。 */
public record CompletePendingRhythmFocusRequest(
        @NotBlank @Size(max = 100) String expectedTitle, @NotNull Boolean completed) {}
