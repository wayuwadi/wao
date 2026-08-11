package cn.wayuwa.wao.agent.dto;

import java.time.LocalDateTime;

/**
 * 已接管的平台账号。绝不包含令牌本身。
 *
 * @param status ACTIVE 可用 / EXPIRED 需重新授权
 */
public record PlatformAccountDto(String id, String platform, String platformLabel, String displayName,
                                 String avatarUrl, String status, String scope, String lastError,
                                 LocalDateTime accessTokenExpiresAt, LocalDateTime createdAt) {
}
