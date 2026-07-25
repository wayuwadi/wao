package cn.wayuwa.wao.dto;

import java.util.List;

/** 第三方登录结果：直接登录，或要求用户认领一个尚未绑定的既有成员。 */
public record AuthResolutionDto(AuthSessionDto session, boolean claimRequired, String provider,
                                String displayName, List<MemberDto> candidates) {
    public static AuthResolutionDto authenticated(AuthSessionDto session) {
        return new AuthResolutionDto(session, false, null, null, List.of());
    }

    public static AuthResolutionDto claim(String provider, String displayName, List<MemberDto> candidates) {
        return new AuthResolutionDto(null, true, provider, displayName, candidates);
    }
}
