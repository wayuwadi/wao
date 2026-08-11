package cn.wayuwa.wao.agent.platform;

import java.time.LocalDateTime;

/**
 * 解密后的平台凭据。只在内存中传递，落库前必须经过 CredentialCipher 加密。
 *
 * @param platformUserId        平台侧用户标识（抖音是 open_id）
 * @param accessToken           访问令牌
 * @param refreshToken          刷新令牌
 * @param scope                 已授权的权限范围
 * @param accessTokenExpiresAt  访问令牌过期时间
 * @param refreshTokenExpiresAt 刷新令牌过期时间
 */
public record PlatformCredential(String platformUserId, String accessToken, String refreshToken,
                                 String scope, LocalDateTime accessTokenExpiresAt,
                                 LocalDateTime refreshTokenExpiresAt) {

    /** 访问令牌是否已过期或即将过期（留 5 分钟余量）。 */
    public boolean needsRefresh(LocalDateTime now) {
        return accessTokenExpiresAt == null || accessTokenExpiresAt.minusMinutes(5).isBefore(now);
    }

    /** 刷新令牌是否已过期，过期后只能让用户重新授权。 */
    public boolean refreshExpired(LocalDateTime now) {
        return refreshTokenExpiresAt != null && refreshTokenExpiresAt.isBefore(now);
    }
}
