package cn.wayuwa.wao.dto;

import java.util.List;

/** 官方可用重置卡数量及发放时间；不将发放时间解释为购买时间。 */
public record CodexResetCreditsDto(Integer availableCount, List<Credit> credits) {
    public record Credit(Long grantedAt, Long expiresAt) { }
}
