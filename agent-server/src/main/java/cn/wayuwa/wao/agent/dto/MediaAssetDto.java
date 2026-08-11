package cn.wayuwa.wao.agent.dto;

/** 上传后的素材。 */
public record MediaAssetDto(String id, String kind, String originalName, String contentType, long byteSize) {
}
