package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 成员在某个小游戏里的唯一活动存档。 */
@TableName("game_save")
public class GameSaveEntity {

    @TableId
    private String id;
    private String memberId;
    private String gameKey;
    private String stateJson;
    private Long score;
    private Integer moveCount;
    private Boolean won;
    private Integer revision;
    private String previousStateJson;
    private Long previousScore;
    private Integer previousMoveCount;
    private LocalDateTime startedAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getGameKey() { return gameKey; }
    public void setGameKey(String gameKey) { this.gameKey = gameKey; }
    public String getStateJson() { return stateJson; }
    public void setStateJson(String stateJson) { this.stateJson = stateJson; }
    public Long getScore() { return score; }
    public void setScore(Long score) { this.score = score; }
    public Integer getMoveCount() { return moveCount; }
    public void setMoveCount(Integer moveCount) { this.moveCount = moveCount; }
    public Boolean getWon() { return won; }
    public void setWon(Boolean won) { this.won = won; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public String getPreviousStateJson() { return previousStateJson; }
    public void setPreviousStateJson(String previousStateJson) { this.previousStateJson = previousStateJson; }
    public Long getPreviousScore() { return previousScore; }
    public void setPreviousScore(Long previousScore) { this.previousScore = previousScore; }
    public Integer getPreviousMoveCount() { return previousMoveCount; }
    public void setPreviousMoveCount(Integer previousMoveCount) { this.previousMoveCount = previousMoveCount; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
