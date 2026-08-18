package cn.wayuwa.wao.dto;

/** Codex 本地客户端返回的点数余额；不包含任何认证信息。 */
public record CodexQuotaCreditsDto(
        boolean hasCredits,
        boolean unlimited,
        String balance) {
}
