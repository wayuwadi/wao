package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Linux Runner 完成页面问答后的结果。 */
public record CompleteAiAssistantRunRequest(
        @NotBlank @Size(max = 64) String claimToken,
        @NotNull Integer exitCode,
        @Size(max = 30000) String response,
        @Size(max = 2000) String error) {
}
