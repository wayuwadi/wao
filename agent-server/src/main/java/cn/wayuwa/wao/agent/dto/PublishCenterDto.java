package cn.wayuwa.wao.agent.dto;

import java.util.List;

/** 发布中心聚合数据。 */
public record PublishCenterDto(AgentSessionDto session, AiStatusDto ai,
                               List<PlatformSummaryDto> platforms, List<PublishTaskDto> tasks) {
}
