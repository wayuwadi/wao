package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 单词本中的一条个人学习记录。 */
public record VocabularyEntryDto(
        String id,
        String term,
        String meaning,
        String context,
        String state,
        int encounterCount,
        int reviewCount,
        LocalDateTime lastEncounteredAt,
        LocalDateTime lastReviewedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
