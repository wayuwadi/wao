package cn.wayuwa.wao.dto;

/**
 * 设置页展示的 AI 账号（不含凭据明文）。
 *
 * <p>{@code quotaPercent} 是当前最紧张的那条限额的已用百分比；查不到额度时为 null，
 * 页面据此显示「未获取」而不是 0%——把没查到当成没用掉，会让人误判还能跑多少任务。
 */
public record AiAccountDto(
        String id,
        String provider,
        String label,
        String email,
        boolean enabled,
        String authMethod,
        String plan,
        String quotaStatus,
        String quotaMessage,
        String quotaWindow,
        Integer quotaPercent,
        String quotaResetsAt,
        String quotaRawJson,
        String tokenExpiresAt,
        String lastSyncedAt,
        String createdAt) {
}
