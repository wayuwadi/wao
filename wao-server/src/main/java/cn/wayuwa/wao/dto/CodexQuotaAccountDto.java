package cn.wayuwa.wao.dto;

import java.util.List;

/** 首页展示的一个本地 Codex 账号额度；同账号的多台 Runner 已合并。 */
public record CodexQuotaAccountDto(
        String key,
        String accountEmail,
        String plan,
        CodexQuotaCreditsDto credits,
        List<CodexQuotaLimitDto> limits,
        List<String> runnerNames,
        String lastSyncedAt,
        boolean fresh) {
}
