package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 当前成员的功能地图使用快照；分数与迭代次数是全局产品事实。 */
public record FunctionHealthOverviewDto(
        int windowDays, LocalDateTime generatedAt, long recentOpens,
        List<FunctionHealthItemDto> functions) {}
