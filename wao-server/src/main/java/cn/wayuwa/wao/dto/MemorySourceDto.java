package cn.wayuwa.wao.dto;

/** 记忆版本的证据引用。 */
public record MemorySourceDto(String type, String sourceId, String url, String excerptHash) { }
