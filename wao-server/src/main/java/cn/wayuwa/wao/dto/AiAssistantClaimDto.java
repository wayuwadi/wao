package cn.wayuwa.wao.dto;

/** Linux Runner 领取到的一次只读页面问答。 */
public record AiAssistantClaimDto(String runId, String prompt, AiRunAccountDto account) {
}
