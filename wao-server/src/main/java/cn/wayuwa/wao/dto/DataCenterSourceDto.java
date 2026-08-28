package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 数据中心中的一项真实数据源及其聚合状态。 */
public record DataCenterSourceDto(
        String id,
        String label,
        String category,
        String description,
        String recordUnit,
        long recordCount,
        LocalDateTime latestAt,
        String status,
        String coverage,
        String boundary) {
}
