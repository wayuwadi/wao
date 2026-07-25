package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 项目级 AI 执行配置。 */
@TableName("ai_project_config")
public class AiProjectConfigEntity {
    @TableId
    private String projectId;
    private Boolean enabled;
    private String repoUrl;
    private String defaultBranch;
    private String workspaceDir;
    private String defaultEngine;
    private String preferredRunnerId;
    private Integer maxParallelRuns;
    private LocalDateTime updatedAt;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
    public String getDefaultEngine() { return defaultEngine; }
    public void setDefaultEngine(String defaultEngine) { this.defaultEngine = defaultEngine; }
    public String getPreferredRunnerId() { return preferredRunnerId; }
    public void setPreferredRunnerId(String preferredRunnerId) { this.preferredRunnerId = preferredRunnerId; }
    public Integer getMaxParallelRuns() { return maxParallelRuns; }
    public void setMaxParallelRuns(Integer maxParallelRuns) { this.maxParallelRuns = maxParallelRuns; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
