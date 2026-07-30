package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 游戏厅历史战绩。 */
public record GameRunRecordDto(
        String id,
        long score,
        int highestTile,
        int moves,
        String outcome,
        LocalDateTime startedAt,
        LocalDateTime endedAt) {
}
