package cn.wayuwa.wao.dto;

import java.util.List;

/** 首页展示的 Agy 额度账号汇总。 */
public record AgyQuotaAccountDto(
        String key,
        String accountLabel,
        List<AgyQuotaGroupDto> groups,
        List<String> sources,
        String lastSyncedAt,
        boolean fresh) {
}
