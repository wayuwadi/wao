package cn.wayuwa.wao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("feishu.login")
public record FeishuLoginProperties(boolean enabled, String appId, String appSecret, String redirectUri,
                                    String frontendUrl, String openApiBaseUrl) { }
