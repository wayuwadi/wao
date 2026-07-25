package cn.wayuwa.wao.dto;

/** 密码明文，只在显式「查看」接口返回一次，不缓存、不出现在列表接口中。 */
public record VaultSecretDto(String secret) {
}
