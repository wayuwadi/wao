package cn.wayuwa.wao.agent.dto;

import java.time.LocalDateTime;

/** 当前工作区可使用的真实素材元数据，不包含节点和磁盘路径。 */
public record MediaAssetDto(String id, String kind, String originalName, String contentType, long byteSize,
                            LocalDateTime createdAt) {
}
