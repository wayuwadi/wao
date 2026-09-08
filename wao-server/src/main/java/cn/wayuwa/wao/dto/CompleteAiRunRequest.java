package cn.wayuwa.wao.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 节点完成 AI CLI 进程后的结果回传。
 *
 * <p>用量字段来自 CLI 的结束事件；Codex 不回传花费，{@code costUsd} 会是 null。
 * 统计解析失败时 Runner 会回传空用量，不影响退出码和结果摘要。
 */
public record CompleteAiRunRequest(
        Integer exitCode,
        String resultSummary,
        String sessionId,
        String model,
        String models,
        Long inputTokens,
        Long outputTokens,
        Long cachedInputTokens,
        Long totalTokens,
        /** Runner 用单调时钟实测的本回合 AI CLI 进程运行毫秒数。 */
        Long aiExecutionDurationMs,
        BigDecimal costUsd,
        /** Runner 从非 Git JSON 产物解析出的结构化项目记忆候选；旧 Runner 缺省为空。 */
        List<ProjectMemoryCandidate> projectMemoryCandidates,
        /** 从本次 Git 新提交提取的 SHA、scope、标题和时间；成功后分别进入更新日记与功能评分。 */
        List<FunctionIterationInput> functionIterations,
        /** Runner 实际加载、失败或不兼容的 Skill 结果。 */
        List<AiSkillAuditDto> skillAudits) {

    public CompleteAiRunRequest(Integer exitCode, String resultSummary, String sessionId, String model,
            String models, Long inputTokens, Long outputTokens, Long cachedInputTokens, Long totalTokens,
            BigDecimal costUsd) {
        this(exitCode, resultSummary, sessionId, model, models, inputTokens, outputTokens,
                cachedInputTokens, totalTokens, null, costUsd, List.of(), List.of(), List.of());
    }

    public CompleteAiRunRequest(Integer exitCode, String resultSummary, String sessionId, String model,
            String models, Long inputTokens, Long outputTokens, Long cachedInputTokens, Long totalTokens,
            BigDecimal costUsd, List<ProjectMemoryCandidate> projectMemoryCandidates) {
        this(exitCode, resultSummary, sessionId, model, models, inputTokens, outputTokens,
                cachedInputTokens, totalTokens, null, costUsd, projectMemoryCandidates, List.of(), List.of());
    }

    public CompleteAiRunRequest(Integer exitCode, String resultSummary, String sessionId, String model,
            String models, Long inputTokens, Long outputTokens, Long cachedInputTokens, Long totalTokens,
            Long aiExecutionDurationMs, BigDecimal costUsd,
            List<ProjectMemoryCandidate> projectMemoryCandidates, List<AiSkillAuditDto> skillAudits) {
        this(exitCode, resultSummary, sessionId, model, models, inputTokens, outputTokens,
                cachedInputTokens, totalTokens, aiExecutionDurationMs, costUsd,
                projectMemoryCandidates, List.of(), skillAudits);
    }
}
