package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 当前成员的网易云音乐连接状态，永不返回令牌。 */
public record NeteaseMusicStatusDto(boolean configured, boolean connected, boolean reauthRequired,
        String nickname, String avatarUrl, LocalDateTime expiresAt, String message) {
}
