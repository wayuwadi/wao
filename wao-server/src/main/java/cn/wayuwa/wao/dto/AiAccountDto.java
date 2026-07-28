package cn.wayuwa.wao.dto;

/** 设置页展示的 AI 账号（不含凭据明文）。 */
public record AiAccountDto(
        String id,
        String provider,
        String label,
        String email,
        boolean enabled,
        String quotaStatus,
        String quotaMessage,
        String quotaRawJson,
        String lastSyncedAt,
        String createdAt) {
}
