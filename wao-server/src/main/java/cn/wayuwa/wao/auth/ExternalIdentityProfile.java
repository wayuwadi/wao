package cn.wayuwa.wao.auth;

import java.io.Serializable;

/** 已由第三方提供方验证、但尚未映射到 WAO 账号的最小身份资料。 */
public record ExternalIdentityProfile(String provider, String subject, String email, String displayName,
                                      String avatarUrl) implements Serializable {
}
