package cn.wayuwa.wao.agent.dto;

import cn.wayuwa.wao.agent.hotspot.HotspotSourceStatus;
import java.util.List;

/** 选题中心聚合数据。 */
public record StudioDto(WorkspaceProfileDto profile, List<HotspotSourceStatus> sources,
                        List<HotspotDto> hotspots, List<TopicSuggestionDto> suggestions) {
}
