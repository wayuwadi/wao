package cn.wayuwa.wao.agent.dto;

import java.time.LocalDateTime;

public record VideoGenerationDto(String id, String prompt, String model, int seconds, String size,
                                 String status, int progress, int attempt, String mediaAssetId,
                                 String contentUrl, String errorMessage, LocalDateTime createdAt,
                                 LocalDateTime updatedAt, LocalDateTime completedAt) { }
