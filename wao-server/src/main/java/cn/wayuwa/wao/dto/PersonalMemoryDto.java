package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 个人记忆列表项；只返回原件元数据，不在 JSON 中返回二进制正文。 */
public record PersonalMemoryDto(String id, String title, String content, String category,
        String captureMode, String mediaType, String fileName, String contentType, Long byteSize,
        LocalDateTime createdAt, LocalDateTime updatedAt, String downloadUrl) { }
