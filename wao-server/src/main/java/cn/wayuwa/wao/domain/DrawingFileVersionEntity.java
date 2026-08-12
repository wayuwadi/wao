package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

/** 草图一次真实内容变化后的历史快照。 */
public class DrawingFileVersionEntity {
    private String fileId;
    private Integer revision;
    private String sceneJson;
    private LocalDateTime createdAt;

    public String getFileId() { return fileId; }
    public void setFileId(String value) { fileId = value; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer value) { revision = value; }
    public String getSceneJson() { return sceneJson; }
    public void setSceneJson(String value) { sceneJson = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
