package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 一局小游戏结束或主动重开时留下的战绩。 */
@TableName("game_run_record")
public class GameRunRecordEntity {

    @TableId
    private String id;
    private String memberId;
    private String gameKey;
    private Long score;
    private Integer highestTile;
    private Integer moveCount;
    private String outcome;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getGameKey() { return gameKey; }
    public void setGameKey(String gameKey) { this.gameKey = gameKey; }
    public Long getScore() { return score; }
    public void setScore(Long score) { this.score = score; }
    public Integer getHighestTile() { return highestTile; }
    public void setHighestTile(Integer highestTile) { this.highestTile = highestTile; }
    public Integer getMoveCount() { return moveCount; }
    public void setMoveCount(Integer moveCount) { this.moveCount = moveCount; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
}
