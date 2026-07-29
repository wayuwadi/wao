package cn.wayuwa.wao.dto;

/**
 * 一次 OAuth 登录的起点。
 *
 * <p>{@code authorizeUrl} 给用户在浏览器里打开，{@code state} 用来找回本次会话。
 * Codex 使用设备码登录并由页面自动轮询；Claude 仍由用户回填授权码。
 */
public record AiAccountLoginStartDto(String state, String authorizeUrl, String expiresAt, String hint,
        String flow, String userCode, long pollIntervalSeconds) {
}
