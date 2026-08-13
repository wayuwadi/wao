package cn.wayuwa.wao.dto;

import java.util.List;

/** 首页自主进化面板：真实会话/周额度、建议清单和自动调度状态。 */
public record AiEvolutionOverviewDto(
        List<AiSessionQuotaDto> sessionQuotas,
        List<AiWeeklyQuotaDto> weeklyQuotas,
        List<AiEvolutionSuggestionDto> suggestions,
        String autoStatus,
        String autoMessage,
        String lastActivityAt,
        String nextEligibleAt,
        String lastCheckedAt,
        String lastRunId) {
}
