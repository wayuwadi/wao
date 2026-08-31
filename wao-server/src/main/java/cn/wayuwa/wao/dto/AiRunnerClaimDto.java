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
        /** 本次 Codex 运行明确选择的模型；其它引擎或自定义 API 为 null。 */
        String model,
        /** 本次 Codex 运行明确选择的推理强度。 */
        String reasoningEffort,
        /** Codex 服务档位；WAO 开发任务固定为 default，不开启 Fast。 */
        String serviceTier,
        /** 是否启用无人值守 Plan 工作流；仅 Codex 为 true。 */
        Boolean planMode,
        /** 是否由 app-server 建立真实持久目标；仅 Codex 为 true。 */
        Boolean goalMode,
        /** Runner 创建目标时使用的稳定、可验证目标描述。 */
        String goalObjective,
        String promptUrl,
        String repoUrl,
        String defaultBranch,
        String workspaceDir,
        String branchName,
        /** AI CLI 异常或非零退出后，在本次运行内静默自动重试的次数。 */
        int failureRetryCount,
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
        List<String> projectMemoryIds,
        /** 入队时固化的审核 Skill；Runner 只装配这里列出的版本。 */
        List<AiSkillCapabilityDto> skills,
        /** 项目管理员配置的任务理解与执行方法；Runner 将其放在不可绕过的交付约束之前。 */
        String executionPrompt) {

    public AiRunnerClaimDto(String runId, String taskId, String taskTitle, String projectId,
            String projectName, String engine, String promptUrl, String repoUrl, String defaultBranch,
            String workspaceDir, String branchName, String previousFailure, String sessionId,
            String continuationInput, AiRunAccountDto account) {
        this(runId, taskId, taskTitle, projectId, projectName, engine, null, null, null, false, false, null,
                promptUrl, repoUrl, defaultBranch, workspaceDir, branchName, 2, previousFailure, sessionId,
                continuationInput, account, List.of(), List.of(), List.of(), null);
    }

    public AiRunnerClaimDto(String runId, String taskId, String taskTitle, String projectId,
            String projectName, String engine, String promptUrl, String repoUrl, String defaultBranch,
            String workspaceDir, String branchName, String previousFailure, String sessionId,
            String continuationInput) {
        this(runId, taskId, taskTitle, projectId, projectName, engine, null, null, null, false, false, null,
                promptUrl, repoUrl, defaultBranch, workspaceDir, branchName, 2, previousFailure, sessionId,
                continuationInput, null, List.of(), List.of(), List.of(), null);
    }
}
