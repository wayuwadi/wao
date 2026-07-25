package cn.wayuwa.wao.dto;

/** 执行节点领取到的一次工作，包含准备仓库和调用 AI CLI 所需的非敏感配置。 */
public record AiRunnerClaimDto(
        String runId,
        String taskId,
        String taskTitle,
        String projectId,
        String projectName,
        String engine,
        String promptUrl,
        String repoUrl,
        String defaultBranch,
        String workspaceDir,
        String branchName) {
}
