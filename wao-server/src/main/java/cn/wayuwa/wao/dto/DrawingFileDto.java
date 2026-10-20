package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 草图文件完整快照。 */
public record DrawingFileDto(
        String id,
        String title,
        String sceneJson,
        int revision,
        LocalDateTime createdAt,
        LocalDateTime updatedAt, String kbId, String docId) {
    public DrawingFileDto(String id, String title, String sceneJson, int revision, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, title, sceneJson, revision, createdAt, updatedAt, null, null);
    }
}
