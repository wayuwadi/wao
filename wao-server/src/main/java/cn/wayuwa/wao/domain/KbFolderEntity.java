package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 知识库文件夹表实体。
 *
 * <p>{@code deletedAt} 非空表示已在回收站；{@code cascadeDel} 为真表示它是随知识库
 * 一起被删的，恢复知识库时会一并恢复，回收站列表里也不单独展示。
 */
@TableName("kb_folder")
public class KbFolderEntity {

    @TableId
    private String id;
    private String kbId;
    private String name;
    private Integer ord;
    private LocalDateTime deletedAt;
    private Boolean cascadeDel;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public Boolean getCascadeDel() { return cascadeDel; }
    public void setCascadeDel(Boolean cascadeDel) { this.cascadeDel = cascadeDel; }
}
