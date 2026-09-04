package cn.wayuwa.wao.agent.dto;

import java.time.LocalDateTime;

/** 一次人工或平台同步得到的文章效果数据，空值表示平台未提供或成员没有填写。 */
public record ContentMetricSnapshotDto(String id, Long views, Long likes, Long comments,
                                       Long favorites, Long shares, LocalDateTime recordedAt) {
}
