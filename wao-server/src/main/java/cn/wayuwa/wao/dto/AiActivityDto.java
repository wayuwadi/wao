package cn.wayuwa.wao.dto;

import java.time.OffsetDateTime;

/** 首页一条可按类型筛选的真实 AI 活动。 */
public record AiActivityDto(
        String id,
        String type,
        String title,
        String status,
        String subtitle,
        String capabilityLabel,
        String targetId,
        String resultPreview,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) { }
