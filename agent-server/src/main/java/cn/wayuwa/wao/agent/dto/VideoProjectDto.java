package cn.wayuwa.wao.agent.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 当前 Agent 工作区唯一的视频制作项目。 */
public record VideoProjectDto(
        String id,
        String title,
        List<VideoSceneDto> scenes,
        int totalSeconds,
        String status,
        int version,
        String outputMediaAssetId,
        String outputName,
        Long outputByteSize,
        String outputUrl,
        LocalDateTime updatedAt,
        LocalDateTime exportedAt) {
}
