package cn.wayuwa.wao.agent.platform;

/**
 * 一次授权换取到的平台身份与凭据。
 *
 * @param displayName 平台昵称，拿不到时回退为平台名 + 用户标识后缀
 * @param avatarUrl   平台头像，可为空
 * @param credential  解密态凭据
 */
public record PlatformIdentity(String displayName, String avatarUrl, PlatformCredential credential) {
}
