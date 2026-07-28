package cn.wayuwa.wao.dto;

/** 额度同步结果。 */
public record AiAccountSyncResultDto(
        String id,
        String provider,
        String status,
        String message,
        String rawJson,
        String syncedAt) {
}
