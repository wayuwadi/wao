package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 数据中心概览，只包含目录和聚合元数据，不暴露各领域正文。 */
public record DataCenterOverviewDto(
        LocalDateTime generatedAt,
        int sourceCount,
        int populatedSourceCount,
        long totalRecords,
        List<DataCenterSourceDto> sources) {
}
