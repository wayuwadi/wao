package cn.wayuwa.wao.dto;

/** 一张已安全持久化的形象实拍图元数据。 */
public record AppearancePhotoDto(String id, String fileName, String contentType, long byteSize, String url) { }
