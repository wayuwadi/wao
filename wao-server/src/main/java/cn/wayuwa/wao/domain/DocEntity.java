package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 文档表实体。正文块（blocks）与出链（links）为 JSON 列，反向链接不落库、按需动态计算。 */
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
}
