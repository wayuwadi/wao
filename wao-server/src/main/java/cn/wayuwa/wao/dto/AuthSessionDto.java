package cn.wayuwa.wao.dto;

import java.util.List;

/** 已登录账号的安全会话资料；不包含任何第三方 token 或密码哈希。 */
public record AuthSessionDto(String accountId, MemberDto member, List<AccountIdentityDto> identities) {
}
