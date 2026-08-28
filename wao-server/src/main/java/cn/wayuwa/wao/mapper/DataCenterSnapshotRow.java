package cn.wayuwa.wao.mapper;

import java.time.LocalDateTime;

/** 数据中心聚合 SQL 的内部投影。 */
public class DataCenterSnapshotRow {

    private String sourceId;
    private Long recordCount;
    private LocalDateTime latestAt;

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public Long getRecordCount() { return recordCount; }
    public void setRecordCount(Long recordCount) { this.recordCount = recordCount; }
    public LocalDateTime getLatestAt() { return latestAt; }
    public void setLatestAt(LocalDateTime latestAt) { this.latestAt = latestAt; }
}
