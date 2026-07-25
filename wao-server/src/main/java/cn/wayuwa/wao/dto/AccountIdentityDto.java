package cn.wayuwa.wao.dto;

/** 当前账号已绑定的一种登录方式。 */
public record AccountIdentityDto(String provider, String email, String displayName) {
}
