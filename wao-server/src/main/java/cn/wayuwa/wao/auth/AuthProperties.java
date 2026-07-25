package cn.wayuwa.wao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 账号认证的阶段性开关。 */
@ConfigurationProperties("wao.auth")
public record AuthProperties(boolean protectionEnabled, boolean registrationEnabled) {
}
