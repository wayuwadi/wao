package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 数字熔炉当前存档、累计数据和最近战绩。 */
public record NumberMergeGameDto(
        List<Integer> board,
        long score,
        int moves,
        boolean won,
        boolean gameOver,
        boolean canUndo,
        int revision,
        LocalDateTime startedAt,
        LocalDateTime updatedAt,
        NumberMergeStatsDto stats,
        List<GameRunRecordDto> recentRuns) {
}
