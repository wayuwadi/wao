package cn.wayuwa.wao.agent.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 一篇已发布内容在某个时间点的真实平台数据快照。 */
@TableName("agent_publish_metric_snapshot")
public class PublishMetricSnapshotEntity {
    private String id;
    private String publishTaskId;
    private String workspaceId;
    private Long viewsCount;
    private Long likesCount;
    private Long commentsCount;
    private Long favoritesCount;
    private Long sharesCount;
    private LocalDateTime recordedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getPublishTaskId() { return publishTaskId; }
    public void setPublishTaskId(String value) { publishTaskId = value; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String value) { workspaceId = value; }
    public Long getViewsCount() { return viewsCount; }
    public void setViewsCount(Long value) { viewsCount = value; }
    public Long getLikesCount() { return likesCount; }
    public void setLikesCount(Long value) { likesCount = value; }
    public Long getCommentsCount() { return commentsCount; }
    public void setCommentsCount(Long value) { commentsCount = value; }
    public Long getFavoritesCount() { return favoritesCount; }
    public void setFavoritesCount(Long value) { favoritesCount = value; }
    public Long getSharesCount() { return sharesCount; }
    public void setSharesCount(Long value) { sharesCount = value; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(LocalDateTime value) { recordedAt = value; }
}
