package cn.wayuwa.wao.dto;

import java.util.List;

/** WAO 页面展示的一次 AI 运行。 */
public record AiRunDto(
        String id,
        String taskId,
        String taskTitle,
        /** 任务目标里的图片预览地址，最多返回前四张；旧内联图片返回受控代理地址。 */
        List<String> previewImages,
        /** 本次只跑这一条子任务；整个任务的运行为 null。 */
        String subId,
        /** 子任务标题，便于页面直接显示跑的是哪一条。 */
        String subTitle,
        String projectId,
        String projectName,
        String engine,
        /** 派工时选择的模型；其它引擎或历史记录为 null。 */
        String requestedModel,
        /** 派工时选择的推理强度；其它引擎或历史记录为 null。 */
        String reasoningEffort,
        /** Codex 服务档位；当前固定为 default，明确表示未开启 Fast。 */
        String serviceTier,
        String status,
        String runnerId,
        String runnerName,
        String requestedRunnerId,
        /** 发起本次运行的 WAO 成员 id。 */
        String requestedBy,
        String branchName,
        /** 本次是在修哪一次失败的运行；不是重跑时为 null。 */
        String retryOfRunId,
        String resultSummary,
        /** AI 为本次运行产出的持久化单文件 HTML 报告；没有报告时为 null。 */
        String reportUrl,
        String latestProgress,
        String progressUpdatedAt,
        List<AiRunProgressDto> progress,
        /** 本次用掉的模型、token 与折算额度。 */
        AiRunUsageDto usage,
        String sessionId,
        Integer exitCode,
        String createdAt,
        String startedAt,
        String finishedAt,
        /** 领取时实际注入给 AI 的项目工程记忆。 */
        List<ProjectMemoryReferenceDto> memoryReads,
        /** 本次成功运行实际沉淀的项目工程记忆。 */
        List<ProjectMemoryReferenceDto> memoryWrites) {
}
