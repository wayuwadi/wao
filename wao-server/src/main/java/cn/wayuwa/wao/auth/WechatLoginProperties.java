package cn.wayuwa.wao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 微信开放平台网站应用 OAuth 配置。 */
@ConfigurationProperties("wechat.login")
public record WechatLoginProperties(boolean enabled, String appId, String appSecret, String redirectUri,
                                    String frontendUrl, String openApiBaseUrl, String authorizeBaseUrl) {
}
