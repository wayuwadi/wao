package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

/** 一条从 Git 提交提取、且所属开发运行已经验收成功的产品更新。 */
public class ProductUpdateRow {
    private String scope;
    private String commitSubject;
    private LocalDateTime committedAt;

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getCommitSubject() { return commitSubject; }
    public void setCommitSubject(String commitSubject) { this.commitSubject = commitSubject; }
    public LocalDateTime getCommittedAt() { return committedAt; }
    public void setCommittedAt(LocalDateTime committedAt) { this.committedAt = committedAt; }
}
