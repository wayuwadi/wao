package cn.wayuwa.wao.dto;

/** 项目级 AI 执行配置。 */
public record AiProjectConfigDto(
        String projectId,
        boolean enabled,
        String repoUrl,
        String defaultBranch,
        String workspaceDir,
        String defaultEngine,
        String preferredRunnerId,
        int maxParallelRuns,
        int failureRetryCount,
        String executionPrompt) {

    /** 兼容不关心提示词的内部调用方与旧测试夹具。 */
    public AiProjectConfigDto(String projectId, boolean enabled, String repoUrl, String defaultBranch,
            String workspaceDir, String defaultEngine, String preferredRunnerId,
            int maxParallelRuns, int failureRetryCount) {
        this(projectId, enabled, repoUrl, defaultBranch, workspaceDir, defaultEngine,
                preferredRunnerId, maxParallelRuns, failureRetryCount, null);
    }
}
