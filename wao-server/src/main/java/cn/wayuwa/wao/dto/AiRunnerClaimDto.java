package cn.wayuwa.wao.dto;

import java.util.List;

/** 执行节点领取到的一次工作，包含准备仓库和调用 AI CLI 所需的配置。 */
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
        String branchName,
        /**
         * 上一次失败运行的报错摘要；本次是重跑时才有值。
         *
         * <p>Runner 会把它拼进给 AI 的提示词，让 AI 接着报错继续修，而不是从零重做一遍。
         */
        String previousFailure,
        String sessionId,
        String continuationInput,
        AiRunAccountDto account,
        /** 按任务关键词筛出的不可信项目背景引用，最多五条、合计不超过一万字。 */
        List<ProjectMemoryContextDto> projectMemories,
        /** 本次实际读取的记忆 id；旧 Runner 可忽略。 */
        List<String> projectMemoryIds) {

    public AiRunnerClaimDto(String runId, String taskId, String taskTitle, String projectId,
            String projectName, String engine, String promptUrl, String repoUrl, String defaultBranch,
            String workspaceDir, String branchName, String previousFailure, String sessionId,
            String continuationInput, AiRunAccountDto account) {
        this(runId, taskId, taskTitle, projectId, projectName, engine, promptUrl, repoUrl,
                defaultBranch, workspaceDir, branchName, previousFailure, sessionId,
                continuationInput, account, List.of(), List.of());
    }

    public AiRunnerClaimDto(String runId, String taskId, String taskTitle, String projectId,
            String projectName, String engine, String promptUrl, String repoUrl, String defaultBranch,
            String workspaceDir, String branchName, String previousFailure, String sessionId,
            String continuationInput) {
        this(runId, taskId, taskTitle, projectId, projectName, engine, promptUrl, repoUrl,
                defaultBranch, workspaceDir, branchName, previousFailure, sessionId,
                continuationInput, null, List.of(), List.of());
    }
}
