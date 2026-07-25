package cn.wayuwa.wao.dto;

/** AI 控制台「工作流」视图里的一个项目：配置加上运行统计。 */
public record AiWorkflowDto(
        String projectId,
        String projectName,
        AiProjectConfigDto config,
        String preferredRunnerName,
        int activeRuns,
        int succeededRuns,
        int failedRuns,
        String lastRunAt) {
}
