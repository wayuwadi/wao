package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.VocabularyEntryEntity;
import cn.wayuwa.wao.dto.CaptureVocabularyEntryDto;
import cn.wayuwa.wao.dto.CaptureVocabularyEntryRequest;
import cn.wayuwa.wao.dto.UpdateVocabularyEntryRequest;
import cn.wayuwa.wao.dto.VocabularyEntryDto;
import cn.wayuwa.wao.mapper.VocabularyEntryMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理成员私有的英语单词与短语学习记录。 */
@Service
public class VocabularyEntryService {

    public static final String LEARNING = "LEARNING";
    public static final String MASTERED = "MASTERED";

    private final VocabularyEntryMapper mapper;

    public VocabularyEntryService(VocabularyEntryMapper mapper) {
        this.mapper = mapper;
    }

    /** 按最近活动时间列出当前成员的全部单词记录。 */
    public List<VocabularyEntryDto> list(String memberId) {
        return mapper.listOwned(memberId).stream().map(this::dto).toList();
    }

    /**
     * 快速收集一个单词；忽略大小写与连续空白去重，重复遇见时累计次数并移到列表顶部。
     *
     * @return 收集后的记录以及本次是否新建
     */
    @Transactional
    public CaptureVocabularyEntryDto capture(CaptureVocabularyEntryRequest request, String memberId) {
        String term = normalizeTerm(request.term());
        String normalized = term.toLowerCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();
        String id = "ve" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        mapper.capture(id, memberId, term, normalized, now);
        VocabularyEntryEntity saved = mapper.findByNormalizedTerm(memberId, normalized);
        if (saved == null) throw new IllegalStateException("单词记录保存后无法读取");
        return new CaptureVocabularyEntryDto(dto(saved), id.equals(saved.getId()));
    }

    /** 更新当前成员的一条学习笔记；访问他人记录按不存在处理。 */
    @Transactional
    public VocabularyEntryDto update(String id, UpdateVocabularyEntryRequest request, String memberId) {
        String state = normalizeState(request.state());
        String meaning = normalizeNote(request.meaning());
        String context = normalizeNote(request.context());
        if (mapper.updateOwned(id, memberId, meaning, context, state, LocalDateTime.now()) == 0) {
            throw notFound();
        }
        return dto(required(id, memberId));
    }

    /** 记录当前成员完成了一次主动复习，并保存本次自评的学习状态。 */
    @Transactional
    public VocabularyEntryDto review(String id, String state, String memberId) {
        if (mapper.reviewOwned(id, memberId, normalizeState(state), LocalDateTime.now()) == 0) {
            throw notFound();
        }
        return dto(required(id, memberId));
    }

    /** 删除当前成员的一条单词记录。 */
    @Transactional
    public void delete(String id, String memberId) {
        if (mapper.deleteOwned(id, memberId) == 0) throw notFound();
    }

    private VocabularyEntryEntity required(String id, String memberId) {
        VocabularyEntryEntity entity = mapper.findOwned(id, memberId);
        if (entity == null) throw notFound();
        return entity;
    }

    private String normalizeTerm(String raw) {
        String term = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (term.isEmpty() || term.length() > 128) {
            throw new BadRequestException("单词或短语须为 1～128 个字符");
        }
        if (!term.matches("(?s).*[A-Za-z].*")) {
            throw new BadRequestException("请输入包含英文字母的单词或短语");
        }
        return term;
    }

    private String normalizeState(String raw) {
        String state = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!LEARNING.equals(state) && !MASTERED.equals(state)) {
            throw new BadRequestException("学习状态不正确");
        }
        return state;
    }

    private String normalizeNote(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private VocabularyEntryDto dto(VocabularyEntryEntity entity) {
        return new VocabularyEntryDto(entity.getId(), entity.getTerm(), entity.getMeaningText(),
                entity.getContextText(), entity.getLearningState(), entity.getEncounterCount(),
                entity.getReviewCount(), entity.getLastEncounteredAt(), entity.getLastReviewedAt(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private NotFoundException notFound() {
        return new NotFoundException("单词记录不存在");
    }
}
