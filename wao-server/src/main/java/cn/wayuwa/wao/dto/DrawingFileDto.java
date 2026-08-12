package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 草图文件完整快照。 */
public record DrawingFileDto(
        String id,
        String title,
        String sceneJson,
        int revision,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
