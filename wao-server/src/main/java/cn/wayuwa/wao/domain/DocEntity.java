package cn.wayuwa.wao.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;

/**
 * 文档表实体。正文块（blocks）与出链（links）为 JSON 列，反向链接不落库、按需动态计算。
 *
 * <p>{@code createdAt / updatedAt} 是真实时间戳；老数据只有 {@code createdLabel / updatedLabel}
 * 这类现成文案，两者都留着，对外优先取时间戳、缺失时回落文案。
 *
 * <p>{@code rev} 每次正文更新自增，协同端据此判断「服务端有没有更新的版本」，
 * 避免每次心跳都把整篇正文拖回来。
 */
@TableName("doc")
public class DocEntity {

    @TableId
    private String id;
    private String kbId;
    private String folderId;
    private String title;
    private String authorId;
    private Boolean auto;
    private String createdLabel;
    private String updatedLabel;
    private String blocksJson;
    private String linksJson;
    private Integer ord;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer rev;
    private LocalDateTime deletedAt;
    private String deletedBy;
    private Boolean cascadeDel;
    private LocalDateTime archivedAt;
    private String archivedBy;
    /** 只能由 DocMapper 的归档 CAS SQL 更新，普通整实体保存永不回写这些归档标识。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String autoSyncScope;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDate autoSyncDate;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long autoSyncVersion;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getFolderId() { return folderId; }
    public void setFolderId(String folderId) { this.folderId = folderId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }
    public Boolean getAuto() { return auto; }
    public void setAuto(Boolean auto) { this.auto = auto; }
    public String getCreatedLabel() { return createdLabel; }
    public void setCreatedLabel(String createdLabel) { this.createdLabel = createdLabel; }
    public String getUpdatedLabel() { return updatedLabel; }
    public void setUpdatedLabel(String updatedLabel) { this.updatedLabel = updatedLabel; }
    public String getBlocksJson() { return blocksJson; }
    public void setBlocksJson(String blocksJson) { this.blocksJson = blocksJson; }
    public String getLinksJson() { return linksJson; }
    public void setLinksJson(String linksJson) { this.linksJson = linksJson; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getRev() { return rev; }
    public void setRev(Integer rev) { this.rev = rev; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String deletedBy) { this.deletedBy = deletedBy; }
    public Boolean getCascadeDel() { return cascadeDel; }
    public void setCascadeDel(Boolean cascadeDel) { this.cascadeDel = cascadeDel; }
    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }
    public String getArchivedBy() { return archivedBy; }
    public void setArchivedBy(String archivedBy) { this.archivedBy = archivedBy; }
    public String getAutoSyncScope() { return autoSyncScope; }
    public void setAutoSyncScope(String autoSyncScope) { this.autoSyncScope = autoSyncScope; }
    public LocalDate getAutoSyncDate() { return autoSyncDate; }
    public void setAutoSyncDate(LocalDate autoSyncDate) { this.autoSyncDate = autoSyncDate; }
    public Long getAutoSyncVersion() { return autoSyncVersion; }
    public void setAutoSyncVersion(Long autoSyncVersion) { this.autoSyncVersion = autoSyncVersion; }
}
