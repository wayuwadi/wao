package cn.wayuwa.wao.dto;

/** 保存项目级 AI 执行配置。 */
public record SaveAiProjectConfigRequest(
        Boolean enabled,
        String repoUrl,
        String defaultBranch,
        String workspaceDir,
        String defaultEngine,
        String preferredRunnerId,
        Integer maxParallelRuns,
        Integer failureRetryCount,
        String executionPrompt) {

    /** 兼容滚动发布期间尚未提交提示词字段的旧页面。 */
    public SaveAiProjectConfigRequest(Boolean enabled, String repoUrl, String defaultBranch,
            String workspaceDir, String defaultEngine, String preferredRunnerId,
            Integer maxParallelRuns, Integer failureRetryCount) {
        this(enabled, repoUrl, defaultBranch, workspaceDir, defaultEngine, preferredRunnerId,
                maxParallelRuns, failureRetryCount, null);
    }
}
