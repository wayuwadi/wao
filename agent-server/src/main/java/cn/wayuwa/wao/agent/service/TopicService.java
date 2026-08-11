package cn.wayuwa.wao.agent.service;

import cn.wayuwa.wao.agent.ai.TopicRecommender;
import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.domain.HotspotEntity;
import cn.wayuwa.wao.agent.domain.TopicSuggestionEntity;
import cn.wayuwa.wao.agent.dto.HotspotDto;
import cn.wayuwa.wao.agent.dto.RecommendRequest;
import cn.wayuwa.wao.agent.dto.StudioDto;
import cn.wayuwa.wao.agent.dto.TopicSuggestionDto;
import cn.wayuwa.wao.agent.hotspot.HotspotSourceStatus;
import cn.wayuwa.wao.agent.mapper.TopicSuggestionMapper;
import cn.wayuwa.wao.agent.platform.PlatformType;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 选题中心：热点 → 结合账号定位推荐 → 落成可执行的选题。 */
@Service
public class TopicService {
    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_DISMISSED = "DISMISSED";
    /** 送给模型评估的热点条数上限，太多会撑爆上下文也拉低挑选质量。 */
    private static final int CANDIDATE_LIMIT = 12;

    private final SharedAccountService accounts;
    private final HotspotService hotspots;
    private final WorkspaceProfileService profiles;
    private final TopicRecommender recommender;
    private final TopicSuggestionMapper suggestionMapper;

    public TopicService(SharedAccountService accounts, HotspotService hotspots,
                        WorkspaceProfileService profiles, TopicRecommender recommender,
                        TopicSuggestionMapper suggestionMapper) {
        this.accounts = accounts;
        this.hotspots = hotspots;
        this.profiles = profiles;
        this.recommender = recommender;
        this.suggestionMapper = suggestionMapper;
    }

    /** 选题中心聚合数据。 */
    public StudioDto studio(String accountId) {
        String workspaceId = accounts.workspaceId(accountId);
        List<HotspotEntity> recent = hotspots.recent(CANDIDATE_LIMIT);
        List<HotspotSourceStatus> statuses = hotspots.statuses();
        List<TopicSuggestionDto> suggestions = suggestionMapper.selectList(
                        Wrappers.<TopicSuggestionEntity>lambdaQuery()
                                .eq(TopicSuggestionEntity::getWorkspaceId, workspaceId)
                                .ne(TopicSuggestionEntity::getStatus, STATUS_DISMISSED)
                                .orderByDesc(TopicSuggestionEntity::getCreatedAt).last("LIMIT 30"))
                .stream().map(this::toDto).toList();
        Map<String, String> labels = statuses.stream().collect(Collectors.toMap(
                HotspotSourceStatus::code, HotspotSourceStatus::label, (a, b) -> a, LinkedHashMap::new));
        return new StudioDto(profiles.get(accountId), statuses,
                recent.stream().map(item -> toDto(item, labels)).toList(), suggestions);
    }

    /** 手动刷新热点。 */
    public List<HotspotSourceStatus> refreshHotspots() {
        return hotspots.refreshAll();
    }

    /** 让模型从当前热榜里挑选题。旧的未使用建议会被清掉，避免页面上堆积过期结果。 */
    public List<TopicSuggestionDto> recommend(String accountId, RecommendRequest request) {
        if (!recommender.available()) {
            throw new BadRequestException("尚未配置文案生成模型，无法做选题推荐。请先设置 AGENT_AI_* 环境变量");
        }
        String workspaceId = accounts.workspaceId(accountId);
        PlatformType platform = PlatformType.parse(request.platform());
        List<HotspotEntity> candidates = hotspots.recent(CANDIDATE_LIMIT);
        if (candidates.isEmpty()) {
            throw new BadRequestException("还没有采集到热点，请先点「刷新热点」");
        }
        int wanted = request.count() == null || request.count() <= 0 ? 5 : Math.min(request.count(), 10);
        var results = recommender.recommend(candidates, platform, profiles.find(workspaceId), wanted);

        suggestionMapper.delete(Wrappers.<TopicSuggestionEntity>lambdaQuery()
                .eq(TopicSuggestionEntity::getWorkspaceId, workspaceId)
                .eq(TopicSuggestionEntity::getStatus, STATUS_NEW));
        LocalDateTime now = LocalDateTime.now();
        for (var item : results) {
            TopicSuggestionEntity entity = new TopicSuggestionEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setWorkspaceId(workspaceId);
            entity.setHotspotId(item.hotspot().getId());
            entity.setPlatform(platform.name());
            entity.setTopic(item.topic());
            entity.setAngle(item.angle());
            entity.setReason(item.reason());
            entity.setFitScore(item.fitScore());
            entity.setStatus(STATUS_NEW);
            entity.setCreatedAt(now);
            suggestionMapper.insert(entity);
        }
        return studio(accountId).suggestions();
    }

    /** 标记建议已被采用或忽略。 */
    public void mark(String accountId, String suggestionId, String status) {
        if (!List.of(STATUS_USED, STATUS_DISMISSED).contains(status)) {
            throw new BadRequestException("不支持的状态：" + status);
        }
        String workspaceId = accounts.workspaceId(accountId);
        int updated = suggestionMapper.update(null, Wrappers.<TopicSuggestionEntity>lambdaUpdate()
                .eq(TopicSuggestionEntity::getId, suggestionId)
                .eq(TopicSuggestionEntity::getWorkspaceId, workspaceId)
                .set(TopicSuggestionEntity::getStatus, status));
        if (updated == 0) throw new BadRequestException("选题建议不存在");
    }

    private TopicSuggestionDto toDto(TopicSuggestionEntity entity) {
        HotspotEntity hotspot = hotspots.find(entity.getHotspotId());
        return new TopicSuggestionDto(entity.getId(), entity.getPlatform(),
                PlatformType.valueOf(entity.getPlatform()).label(), entity.getTopic(), entity.getAngle(),
                entity.getReason(), entity.getFitScore(), entity.getStatus(), entity.getHotspotId(),
                hotspot == null ? null : hotspot.getTitle(), hotspot == null ? null : hotspot.getUrl(),
                entity.getCreatedAt());
    }

    private HotspotDto toDto(HotspotEntity entity, Map<String, String> labels) {
        return new HotspotDto(entity.getId(), entity.getSource(),
                labels.getOrDefault(entity.getSource(), entity.getSource()),
                entity.getTitle(), entity.getSummary(), entity.getUrl(), entity.getHeat(),
                entity.getRankNo(), entity.getFirstSeenAt(), entity.getLastSeenAt());
    }
}
