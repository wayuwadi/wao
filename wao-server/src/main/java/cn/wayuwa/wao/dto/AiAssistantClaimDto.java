package cn.wayuwa.wao.dto;

import java.util.List;

/** Linux Runner 领取到的一次只读页面问答。 */
public record AiAssistantClaimDto(
        String runId, String claimToken, String prompt, int maxRuntimeSeconds,
        AiRunAccountDto account, String mode, List<String> referenceImageIds, String model) {

    public AiAssistantClaimDto(String runId, String claimToken, String prompt, int maxRuntimeSeconds,
            AiRunAccountDto account, String mode) {
        this(runId, claimToken, prompt, maxRuntimeSeconds, account, mode, List.of(), null);
    }

    public AiAssistantClaimDto(String runId, String claimToken, String prompt, int maxRuntimeSeconds,
            AiRunAccountDto account) {
        this(runId, claimToken, prompt, maxRuntimeSeconds, account, "ASSISTANT", List.of(), null);
    }
}
