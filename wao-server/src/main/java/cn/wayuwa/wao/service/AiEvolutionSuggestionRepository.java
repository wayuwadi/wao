package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.AiEvolutionSuggestionEntity;
import cn.wayuwa.wao.dto.AiEvolutionSuggestionDto;
import cn.wayuwa.wao.mapper.AiEvolutionSuggestionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 保存并读取 AI 从真实 WAO 资产中挖掘出的任务建议批次。 */
@Service
public class AiEvolutionSuggestionRepository {

    private final AiEvolutionSuggestionMapper mapper;

    public AiEvolutionSuggestionRepository(AiEvolutionSuggestionMapper mapper) {
        this.mapper = mapper;
    }

    /** 返回最近完整保存的一批建议；尚未生成时返回空列表。 */
    public List<AiEvolutionSuggestionDto> latest() {
        AiEvolutionSuggestionEntity newest = mapper.selectOne(
                new LambdaQueryWrapper<AiEvolutionSuggestionEntity>()
                        .orderByDesc(AiEvolutionSuggestionEntity::getCreatedAt)
                        .orderByDesc(AiEvolutionSuggestionEntity::getBatchId)
                        .last("LIMIT 1"));
        if (newest == null) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<AiEvolutionSuggestionEntity>()
                        .eq(AiEvolutionSuggestionEntity::getBatchId, newest.getBatchId())
                        .orderByAsc(AiEvolutionSuggestionEntity::getOrd))
                .stream().map(AiEvolutionSuggestionRepository::toDto).toList();
    }

    /** 按不可猜测 id 读取一条曾经展示过的建议，刷新后已打开页面仍可安全派发。 */
    public AiEvolutionSuggestionDto find(String id) {
        AiEvolutionSuggestionEntity entity = mapper.selectById(id);
        return entity == null ? null : toDto(entity);
    }

    /** 在一个事务里落下完整的一批建议，避免页面读到半批数据。 */
    @Transactional
    public void saveBatch(String batchId, List<AiEvolutionSuggestionDto> suggestions) {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < suggestions.size(); i++) {
            AiEvolutionSuggestionDto suggestion = suggestions.get(i);
            AiEvolutionSuggestionEntity entity = new AiEvolutionSuggestionEntity();
            entity.setId(suggestion.id());
            entity.setBatchId(batchId);
            entity.setOrd(i);
            entity.setTitle(suggestion.title());
            entity.setDescription(suggestion.description());
            entity.setIcon(suggestion.icon());
            entity.setGoal(suggestion.goal());
            entity.setSourceTitle(suggestion.sourceTitle());
            entity.setSourceUrl(suggestion.sourceUrl());
            entity.setCreatedAt(now);
            mapper.insert(entity);
        }
    }

    private static AiEvolutionSuggestionDto toDto(AiEvolutionSuggestionEntity entity) {
        return new AiEvolutionSuggestionDto(entity.getId(), entity.getTitle(), entity.getDescription(),
                entity.getIcon(), entity.getGoal(), entity.getSourceTitle(), entity.getSourceUrl());
    }
}
