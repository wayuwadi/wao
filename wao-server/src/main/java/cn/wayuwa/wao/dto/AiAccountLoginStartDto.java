package cn.wayuwa.wao.dto;

/**
 * 一次 OAuth 登录的起点。
 *
 * <p>{@code authorizeUrl} 给用户在浏览器里打开，{@code state} 回填时用来找回本次会话；
 * PKCE 的 code_verifier 只留在服务端，不出现在这里。
 */
public record AiAccountLoginStartDto(String state, String authorizeUrl, String expiresAt, String hint) {
}
