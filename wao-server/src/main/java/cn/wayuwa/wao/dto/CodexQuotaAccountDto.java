package cn.wayuwa.wao.dto;

import java.util.List;

/** 首页展示的一个 Codex 账号额度；同账号多来源已合并，runnerNames 沿用旧字段名表达采集来源。 */
public record CodexQuotaAccountDto(
        String key,
        String accountEmail,
        String plan,
        CodexQuotaCreditsDto credits,
        List<CodexQuotaLimitDto> limits,
        List<String> runnerNames,
        String lastSyncedAt,
        boolean fresh,
        CodexResetCreditsDto resetCredits) {
    public CodexQuotaAccountDto(String key, String email, String plan, CodexQuotaCreditsDto credits,
            List<CodexQuotaLimitDto> limits, List<String> runnerNames, String lastSyncedAt, boolean fresh) {
        this(key, email, plan, credits, limits, runnerNames, lastSyncedAt, fresh, null);
    }
}
