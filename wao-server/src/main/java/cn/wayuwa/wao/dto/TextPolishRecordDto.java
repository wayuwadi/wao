package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 当前成员可查看的一次润色前后对照。 */
public record TextPolishRecordDto(
        String id,
        String promptSnapshot,
        String originalText,
        String polishedText,
        LocalDateTime createdAt) { }
