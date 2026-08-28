package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 当前成员一次已成功完成的真实 AI 润色记录。 */
@TableName("text_polish_record")
public class TextPolishRecordEntity {
    @TableId
    private String id;
    private String memberId;
    private String promptSnapshot;
    private String originalText;
    private String polishedText;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getPromptSnapshot() { return promptSnapshot; }
    public void setPromptSnapshot(String promptSnapshot) { this.promptSnapshot = promptSnapshot; }
    public String getOriginalText() { return originalText; }
    public void setOriginalText(String originalText) { this.originalText = originalText; }
    public String getPolishedText() { return polishedText; }
    public void setPolishedText(String polishedText) { this.polishedText = polishedText; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
