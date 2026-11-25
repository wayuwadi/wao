package cn.wayuwa.wao.dto;

import java.util.List;

/** 脱敏后的 Agy 额度上报快照。 */
public record AgyQuotaReport(String accountLabel, List<AgyQuotaGroupDto> groups) {
}
