package cn.wayuwa.wao.auth;

import java.io.Serializable;

/** 已由第三方提供方验证、但尚未映射到 WAO 账号的最小身份资料。 */
public record ExternalIdentityProfile(String provider, String subject, String email, String displayName,
                                      String avatarUrl, String unionSubject) implements Serializable {

    /** 非飞书提供方没有跨应用 Union ID，保留原有五参数调用方式。 */
    public ExternalIdentityProfile(String provider, String subject, String email, String displayName,
            String avatarUrl) {
        this(provider, subject, email, displayName, avatarUrl, null);
    }
}
