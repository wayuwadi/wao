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
        int failureRetryCount) {
}
