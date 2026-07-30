package cn.wayuwa.wao.dto;

/** 数字熔炉的长期累计数据，包含当前尚未归档的一局。 */
public record NumberMergeStatsDto(
        long bestScore,
        int highestTile,
        long gamesPlayed,
        long totalMoves,
        long wins) {
}
