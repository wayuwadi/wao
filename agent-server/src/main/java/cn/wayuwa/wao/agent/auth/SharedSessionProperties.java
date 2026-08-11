package cn.wayuwa.wao.agent.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** WAO 共享会话配置；localAccountId 只能用于本地开发。 */
@ConfigurationProperties(prefix = "wao-agent.shared-session")
public record SharedSessionProperties(String localAccountId) {
}
