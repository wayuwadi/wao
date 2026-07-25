package cn.wayuwa.wao.dto;

/** 一次性节点配对码，只在创建时返回明文。 */
public record AiPairCodeDto(String code, String expiresAt) {
}
