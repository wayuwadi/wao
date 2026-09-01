package cn.wayuwa.wao.dto;

/** 新生成的集成令牌，只在轮换响应中返回一次。 */
public record RotatedIntegrationTokenDto(String type, String token) { }
