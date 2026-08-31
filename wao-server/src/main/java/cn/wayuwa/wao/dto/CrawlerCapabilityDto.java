package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 爬虫能力详情；源码只通过已登录的站内接口返回。 */
public record CrawlerCapabilityDto(
        String id,
        String name,
        String description,
        String language,
        String sourceCode,
        boolean enabled,
        int revision,
        String createdBy,
        String updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) { }
