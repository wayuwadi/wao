package cn.wayuwa.wao.dto;

/** 设备令牌的一项上下文授权。 */
public record MemoryGrantDto(String scopeType, String scopeId, boolean canRead,
                             boolean canPropose, boolean canDirectWrite) { }
