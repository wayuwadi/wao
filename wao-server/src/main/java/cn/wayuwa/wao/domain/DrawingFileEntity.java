package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 归属到单个成员的 Excalidraw 草图文件。 */
@TableName("drawing_file")
public class DrawingFileEntity {
    @TableId private String id;
    private String ownerMemberId;
    private String title;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false) private String kbId;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false) private String docId;
    public String getKbId() { return kbId; }
    public void setKbId(String value) { kbId = value; }
    public String getDocId() { return docId; }
    public void setDocId(String value) { docId = value; }
    private String sceneJson;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(String value) { ownerMemberId = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getSceneJson() { return sceneJson; }
    public void setSceneJson(String value) { sceneJson = value; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer value) { revision = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
