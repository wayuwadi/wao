package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一天的听说读写训练与自我复盘记录。 */
public record EnglishLearningSessionDto(
        String id,
        LocalDate date,
        String lessonId,
        boolean listeningDone,
        boolean speakingDone,
        boolean readingDone,
        boolean writingDone,
        int practiceMinutes,
        Integer confidence,
        String writingText,
        String reflection,
        String nextFocus,
        LocalDateTime completedAt,
        LocalDateTime updatedAt) { }
