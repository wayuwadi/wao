package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 草图文件列表元数据，不包含大体积画布 JSON。 */
public record DrawingFileSummaryDto(
        String id,
        String title,
        int revision,
        LocalDateTime createdAt,
        LocalDateTime updatedAt, String kbId, String docId) {
    public DrawingFileSummaryDto(String id, String title, int revision, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, title, revision, createdAt, updatedAt, null, null);
    }
}
