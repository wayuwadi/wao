package cn.wayuwa.wao.dto;

/** 保存项目级 AI 执行配置。 */
public record SaveAiProjectConfigRequest(
        Boolean enabled,
        String repoUrl,
        String defaultBranch,
        String workspaceDir,
        String defaultEngine,
        String preferredRunnerId,
        Integer maxParallelRuns) {
}
