package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 可供当前成员恢复的草图历史版本元数据。 */
public record DrawingFileVersionDto(int revision, LocalDateTime createdAt) {
}
