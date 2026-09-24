package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 当前成员的一张参考图元数据，不包含图片正文。 */
public record ReferenceImageDto(String id, String name, String contentType, long byteSize,
        LocalDateTime createdAt, String url) { }
