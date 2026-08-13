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
        String lastRunAt,
        boolean configured,
        boolean projectDeleted) {

    /** 兼容只关心运行统计的内部调用；这类工作流来自仍存在的项目和已保存配置。 */
    public AiWorkflowDto(String projectId, String projectName, AiProjectConfigDto config,
            String preferredRunnerName, int activeRuns, int succeededRuns, int failedRuns, String lastRunAt) {
        this(projectId, projectName, config, preferredRunnerName, activeRuns, succeededRuns, failedRuns,
                lastRunAt, true, false);
    }
}
