package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** Runner 回传的用户可读进展。 */
public record ReportAiRunProgressRequest(
        @NotBlank String kind,
        @NotBlank String content,
        String goalObjective,
        String goalStatus,
        Long goalTokensUsed,
        Long goalTimeUsedSeconds) {

    public ReportAiRunProgressRequest(String kind, String content) {
        this(kind, content, null, null, null, null);
    }
}
