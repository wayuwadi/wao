package cn.wayuwa.wao.domain;

/** MyBatis 聚合查询承载对象。 */
public class GameRunSummary {
    private Long gamesPlayed;
    private Long bestScore;
    private Integer highestTile;
    private Long totalMoves;
    private Long wins;

    public Long getGamesPlayed() { return gamesPlayed; }
    public void setGamesPlayed(Long gamesPlayed) { this.gamesPlayed = gamesPlayed; }
    public Long getBestScore() { return bestScore; }
    public void setBestScore(Long bestScore) { this.bestScore = bestScore; }
    public Integer getHighestTile() { return highestTile; }
    public void setHighestTile(Integer highestTile) { this.highestTile = highestTile; }
    public Long getTotalMoves() { return totalMoves; }
    public void setTotalMoves(Long totalMoves) { this.totalMoves = totalMoves; }
    public Long getWins() { return wins; }
    public void setWins(Long wins) { this.wins = wins; }
}
