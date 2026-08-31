package cn.wayuwa.wao.dto;

/** 首页“可验收的任务”中的一条真实任务及其当前处理动作。 */
public record ReviewableTaskDto(
        String taskId,
        String title,
        String projectId,
        String projectName,
        String priority,
        String taskStatus,
        /** ACCEPTANCE 表示打开任务验收；RETRY 表示先查看最近失败运行并基于报错重跑。 */
        String action,
        String runId,
        String deliveryStage,
        String resultPreview,
        String finishedAt) { }
