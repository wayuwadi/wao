package cn.wayuwa.wao.dto;

import java.time.OffsetDateTime;

/** 自动聊天复盘的持久化策略与最近调度结果。 */
public record ChatReviewAutoConfigDto(
        boolean enabled,
        int intervalSeconds,
        String rangeKind,
        int rangeValue,
        String focus,
        String aiCapabilityId,
        OffsetDateTime nextFireAt,
        String lastRunId,
        OffsetDateTime updatedAt) { }
