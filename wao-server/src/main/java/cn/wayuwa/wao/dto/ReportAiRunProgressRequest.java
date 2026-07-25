package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** Runner 回传的用户可读进展。 */
public record ReportAiRunProgressRequest(@NotBlank String kind, @NotBlank String content) {
}
